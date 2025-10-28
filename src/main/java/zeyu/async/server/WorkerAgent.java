package zeyu.async.server;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;

import zeyu.async.common.ZkFutures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TasksAssignee - Worker管理器
 * 
 * 作用：管理单个worker的生命周期，包括注册、任务接收和处理。
 * 整合了worker注册和任务接收功能，避免与WorkersWatcher的功能重叠。
 * 
 * 设计要点：
 * 1) 注册 worker 存在性节点，让主节点知道当前worker可用
 * 2) 监听 /assign/{workerId} 路径变化，接收分配给当前worker的任务
 * 3) 所有操作在单线程执行器(exec)上串行执行，避免竞态条件
 * 4) 支持任务处理回调，处理接收到的任务
 * 5) 异常自愈：连接丢失、超时等异常通过重试机制自动恢复
 * 
 * 工作流程：
 * 1) 注册 worker 存在性节点，通知主节点当前worker可用
 * 2) 监听 /assign/{workerId} 路径，等待任务分配
 * 3) 接收任务后调用处理回调，处理完成后删除任务节点
 * 4) 继续监听新任务
 * 
 * 与WorkersWatcher的关系：
 * - WorkersWatcher: 主节点侧监控所有worker的变化
 * - TasksAssignee: 从节点侧管理单个worker的生命周期
 * 
 * 路径结构：
 * - /workers/{workerId} - Worker存在性节点（临时节点）
 * - /assign/{workerId}/{taskId} - 分配给当前worker的任务
 * 
 * @author zeyu
 */
public class WorkerAgent implements Watcher, AutoCloseable {

    /** ZooKeeper 异步操作封装 */
    private final ZkFutures zf;

    /** Worker ID，唯一标识当前worker */
    private final String workerId;

    /** Workers路径，如 "/workers" */
    private final String workersPath;

    /** 当前worker的分配目录，如 "/assign/{workerId}" */
    private final String myAssignDir;

    /** 当前worker的存在性节点，如 "/workers/{workerId}" */
    private final String myPresenceZnode;

    /** 结果状态根路径，如 "/status" */
    private final String statusPath;

    /** 单线程执行器，确保所有任务接收操作串行执行，避免竞态条件 */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "tasksAssignee");
        t.setDaemon(true);
        return t;
    });

    /** 停止标志，用于优雅关闭 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 任务处理回调：接收任务数据和状态信息，返回处理结果 */
    private final Consumer<ZkFutures.NodeData> taskHandler;

    /** 空子节点快照常量，用于处理空任务列表 */
    private static final ZkFutures.ChildrenSnapshot EMPTY_CHILDREN_SNAPSHOT = new ZkFutures.ChildrenSnapshot(
            Collections.emptyList(), null);

    /**
     * 构造函数
     * 
     * @param zf          ZooKeeper异步操作封装
     * @param workerId    Worker唯一标识
     * @param workersPath Workers路径
     * @param assignPath  任务分配路径
     * @param statusPath  状态根路径
     * @param taskHandler 任务处理回调
     */
    public WorkerAgent(ZkFutures zf, String workerId, String workersPath, String assignPath, String statusPath,
            Consumer<ZkFutures.NodeData> taskHandler) {
        this.zf = zf;
        this.workerId = workerId;
        this.workersPath = workersPath;
        this.taskHandler = taskHandler;
        this.myAssignDir = assignPath + "/" + workerId;
        this.myPresenceZnode = workersPath + "/" + workerId;
        this.statusPath = statusPath;
    }

    /**
     * 启动任务接收器
     * 
     * 注册worker存在性节点，开始监听任务分配。
     * 
     * @return 启动完成的Future
     */
    public CompletableFuture<Void> start() {
        // 注册worker存在性节点，通知主节点当前worker可用
        return zf.ensurePersistent(workersPath)
                .thenComposeAsync(
                        v -> zf.createEphemeral(myPresenceZnode, workerId.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE),
                        exec)
                .thenComposeAsync(v -> zf.ensurePersistent(statusPath), exec)
                .thenComposeAsync(v -> refreshWorkerTasks(), exec);
    }

    /**
     * 刷新worker任务列表
     * 
     * 获取当前分配给worker的所有任务，并发处理这些任务。
     * 所有操作在exec线程池上串行执行，确保线程安全。
     * 
     * @return 任务处理完成的Future
     */
    private CompletableFuture<Void> refreshWorkerTasks() {
        if (stopped.get()) {
            return CompletableFuture.completedFuture(null);
        }

        return zf.ensurePersistent(myAssignDir)
                .thenComposeAsync(v -> zf.getChildrenOrEmpty(myAssignDir, this), exec)
                .thenComposeAsync(opt -> {
                    if (stopped.get())
                        return CompletableFuture.completedFuture(null);
                    var ids = opt.orElse(EMPTY_CHILDREN_SNAPSHOT).children();
                    if (ids.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    var list = new ArrayList<CompletableFuture<Void>>(ids.size());
                    for (String taskId : ids) {
                        list.add(processTask(taskId));
                    }
                    return CompletableFuture.allOf(list.toArray(CompletableFuture[]::new));
                }, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /**
     * 处理单个任务
     * 
     * 获取任务数据，调用处理回调，处理完成后删除任务节点。
     * 
     * @param taskId 任务ID
     * @return 处理完成的Future
     */
    private CompletableFuture<Void> processTask(String taskId) {
        if (stopped.get())
            return CompletableFuture.completedFuture(null);

        String taskPath = myAssignDir + "/" + taskId;

        return zf.getData(taskPath, null)
                .thenComposeAsync(data -> {
                    // 校验分配里的 fencing token（若存在）与当前 /master 的 czxid 一致或最新
                    return zf.exists("/master", null).thenComposeAsync(masterOpt -> {
                        long current = masterOpt.map(s -> s.getCzxid()).orElse(-1L);
                        byte[] raw = data.data();
                        String prefix = "token:";
                        int nl = -1;
                        if (raw.length > prefix.length()) {
                            // 简单解析 header: token:<czxid>\n...
                            String head = new String(raw, 0, Math.min(raw.length, 64));
                            if (head.startsWith(prefix) && (nl = head.indexOf('\n')) > 0) {
                                try {
                                    long tok = Long.parseLong(head.substring(prefix.length(), nl));
                                    if (current >= 0 && tok < current) {
                                        // 过期任务：直接丢弃并删除分配
                                        return zf.delete(taskPath, -1).exceptionally(e -> null).thenApply(v -> null);
                                    }
                                } catch (NumberFormatException ignore) {
                                }
                            }
                        }

                        // 去掉 header 后的真实任务数据传给处理回调
                        ZkFutures.NodeData effective = data;
                        if (nl > 0) {
                            int headerLen = nl + 1; // 包含换行
                            byte[] body = new byte[raw.length - headerLen];
                            System.arraycopy(raw, headerLen, body, 0, body.length);
                            effective = new ZkFutures.NodeData(body, data.stat());
                        }

                        try {
                            taskHandler.accept(effective);
                            String statusZ = statusPath + "/" + taskId;
                            byte[] ok = "OK".getBytes();
                            return upsertStatus(statusZ, ok)
                                    .thenComposeAsync(v -> zf.delete(taskPath, -1), exec);
                        } catch (Exception e) {
                            System.err.println("Task processing failed for " + taskId + ": " + e.getMessage());
                            String statusZ = statusPath + "/" + taskId;
                            byte[] err = ("ERR:" + e.getMessage()).getBytes();
                            return upsertStatus(statusZ, err)
                                    .exceptionally(ex -> null)
                                    .thenComposeAsync(v -> zf.delete(taskPath, -1).exceptionally(ex -> null), exec)
                                    .thenApply(x -> null);
                        }
                    }, exec);
                }, exec)
                .exceptionally(ex -> {
                    // 删除失败也记录日志，但不影响其他任务
                    System.err.println("Failed to delete task " + taskId + ": " + ex.getMessage());
                    return null;
                });
    }

    private CompletableFuture<Void> upsertStatus(String statusZ, byte[] data) {
        return zf.setData(statusZ, data, -1)
                .thenApply(s -> (Void) null)
                .handle((v, ex) -> {
                    if (ex == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return zf.createPersistent(statusZ, data, ZooDefs.Ids.OPEN_ACL_UNSAFE)
                            .thenApply(x -> (Void) null)
                            .exceptionally(e -> null);
                })
                .thenCompose(f -> f);
    }

    /**
     * 失败自愈机制
     * 
     * 当任务处理失败时，通过重试机制自动恢复。
     * 支持连接丢失和操作超时等异常的重试。
     * 
     * @return 自愈完成的Future
     */
    private CompletableFuture<Void> onFailGoSelfHeal() {
        if (stopped.get())
            return CompletableFuture.completedFuture(null);

        // 简单的重试机制：延迟后重新刷新任务
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000); // 延迟1秒
                if (!stopped.get()) {
                    refreshWorkerTasks();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, exec).thenApply(v -> null);
    }

    /**
     * ZooKeeper 事件处理器
     * 
     * 处理ZooKeeper连接状态变化和任务分配路径变化事件。
     * 在连接恢复时刷新任务，在任务路径变化时重新处理任务。
     * 
     * @param event ZooKeeper事件
     */
    @Override
    public void process(WatchedEvent event) {
        if (stopped.get())
            return;

        if (event.getState() == Event.KeeperState.Expired) {
            exec.submit(() -> {
                /* 等 SyncConnected 再refresh */});
            return;
        }

        if (event.getType() == Event.EventType.None && event.getState() == Event.KeeperState.SyncConnected) {
            exec.submit(this::refreshWorkerTasks);
            return;
        }

        if (event.getType() == Event.EventType.NodeChildrenChanged && myAssignDir.equals(event.getPath())) {
            exec.submit(this::refreshWorkerTasks);
        }
    }

    /**
     * 关闭任务接收器
     * 
     * 优雅关闭：停止任务处理，关闭线程池，释放资源。
     * 确保所有正在进行的任务能够正常完成。
     */
    @Override
    public void close() throws Exception {
        if (stopped.compareAndSet(false, true)) {
            exec.shutdown();
        }
    }
}
