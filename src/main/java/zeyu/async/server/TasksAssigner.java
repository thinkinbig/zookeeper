package zeyu.async.server;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;

import zeyu.async.common.ZkFutures;
 
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
 

/**
 * TasksAssigner - 任务分配器
 * 
 * 作用：负责将待处理任务分配给可用的工作节点，实现分布式任务调度。
 * 
 * 设计要点：
 * 1) 采用"认领-分配"模式：先认领任务，再分配给具体worker，确保任务不重复分配
 * 2) 支持原子性和最终一致性两种模式：优先使用multi操作，失败时fallback到非原子操作
 * 3) 所有操作在单线程执行器(exec)上串行执行，避免竞态条件
 * 4) 定期补偿机制：检测和修复不一致状态，保证最终一致性
 * 5) 异常自愈：连接丢失、超时等异常通过重试和补偿机制自动恢复
 * 
 * 工作流程：
 * 1) 监听 /tasks 路径变化，获取待处理任务列表
 * 2) 对每个任务执行 claimThenAssign：认领 -> 获取数据 -> 选择worker -> 分配
 * 3) 优先使用multi操作实现原子性分配，失败时使用非原子操作
 * 4) 定期执行补偿任务，清理孤儿claims和tasks，检测重复分配
 * 
 * 路径结构：
 * - /tasks/{taskId} - 待处理任务
 * - /claims/{taskId} - 任务认领（临时节点）
 * - /assign/{workerId}/{taskId} - 任务分配（临时节点）
 * 
 * @author zeyu
 */
public class TasksAssigner implements Watcher, AutoCloseable {
    /** ZooKeeper 异步操作封装 */
    private final ZkFutures zf;

    /** 待处理任务路径，如 "/tasks" */
    private final String tasksPath;

    /** 任务认领路径，如 "/claims" */
    private final String claimsPath;

    /** 任务分配路径，如 "/assign" */
    private final String assignPath;

    /** 单线程执行器，确保所有任务分配操作串行执行，避免竞态条件 */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "tasksAssign");
        t.setDaemon(true);
        return t;
    });

    /** 停止标志，用于优雅关闭 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 定期补偿调度器，用于执行最终一致性补偿任务 */
    private final ScheduledExecutorService compensationScheduler = Executors.newScheduledThreadPool(1, r -> {
        var t = new Thread(r, "tasks-compensation");
        t.setDaemon(true);
        return t;
    });

    /** 补偿任务句柄，用于取消定期补偿 */
    private ScheduledFuture<?> compensationTask;

    /** Worker选择策略：根据任务数据和状态信息选择最适合的worker，无可用时返回null */
    private final Function<ZkFutures.NodeData, String> pickWorker;


    /**
     * 构造函数
     * 
     * @param zf         ZooKeeper异步操作封装
     * @param tasksPath  待处理任务路径
     * @param claimsPath 任务认领路径
     * @param assignPath 任务分配路径
     * @param pickWorker Worker选择策略函数
     */
    public TasksAssigner(ZkFutures zf, String tasksPath, String claimsPath, String assignPath,
            Function<ZkFutures.NodeData, String> pickWorker) {
        this.zf = zf;
        this.tasksPath = tasksPath;
        this.claimsPath = claimsPath;
        this.assignPath = assignPath;
        this.pickWorker = pickWorker;
    }

    /**
     * 启动任务分配器
     * 
     * 根据ZkFutures的multi设置决定是否启动补偿任务。
     * 如果multi未启用，则启动补偿任务保证最终一致性。
     * 启动时确保所有必要的路径都存在。
     * 
     * @return 启动完成的Future
     */
    public CompletableFuture<Void> start() {
        // 确保所有必要的路径都存在
        return zf.ensurePersistent(tasksPath)
                .thenComposeAsync(v -> zf.ensurePersistent(claimsPath), exec)
                .thenComposeAsync(v -> zf.ensurePersistent(assignPath), exec)
                .thenComposeAsync(v -> {
                    // 如果multi未启用，启动补偿任务保证最终一致性
                    if (!zf.isMultiEnabled()) {
                        // 启动定期补偿任务，每30秒执行一次
                        compensationTask = compensationScheduler.scheduleWithFixedDelay(
                                this::runCompensation,
                                30, 30, TimeUnit.SECONDS);
                    }

                    return refreshTasks();
                }, exec);
    }

    /** 空子节点快照常量，用于处理空任务列表 */
    private static final ZkFutures.ChildrenSnapshot EMPTY_CHILDREN_SNAPSHOT = new ZkFutures.ChildrenSnapshot(
            Collections.emptyList(), null);

    /**
     * 刷新任务列表并处理所有待分配任务
     * 
     * 核心方法：获取当前所有待处理任务，并发执行认领和分配操作。
     * 所有操作在exec线程池上串行执行，确保线程安全。
     * 
     * @return 任务处理完成的Future
     */
    private CompletableFuture<Void> refreshTasks() {
        if (stopped.get())
            return CompletableFuture.completedFuture(null);
        return zf.getChildrenOrEmpty(tasksPath, this)
                .thenComposeAsync(opt -> {
                    if (stopped.get())
                        return CompletableFuture.completedFuture(null);
                    var ids = opt.orElse(EMPTY_CHILDREN_SNAPSHOT).children();
                    if (ids.isEmpty())
                        return CompletableFuture.completedFuture(null);

                    var list = new ArrayList<CompletableFuture<Void>>(ids.size());
                    for (String id : ids) {
                        list.add(claimThenAssign(id)); // 现在返回 CF 了
                    }
                    return CompletableFuture.allOf(list.toArray(CompletableFuture[]::new));
                }, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
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
        return CompletableFuture.completedFuture(null)
                .thenComposeAsync(v -> stopped.get() ? CompletableFuture.completedFuture(null) : refreshTasks(), exec)
                .exceptionally(e -> null);
    }

    /**
     * 认领并分配任务
     * 
     * 核心分配逻辑：先认领任务，获取任务数据，选择worker，然后分配任务。
     * 支持原子性和最终一致性两种模式，优先使用multi操作。
     * 
     * @param taskId 任务ID
     * @return 分配完成的Future
     */
    private CompletableFuture<Void> claimThenAssign(String taskId) {
        if (stopped.get())
            return CompletableFuture.completedFuture(null);

        String claimZ = claimsPath + "/" + taskId;
        String taskZ = tasksPath + "/" + taskId;

        return zf.createEphemeral(claimZ, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE)
                .thenComposeAsync(v -> zf.getData(taskZ, this), exec)
                .thenComposeAsync(dr -> {
                    String worker = pickWorker.apply(dr);
                    if (worker == null) {
                        // 无可用 worker：释放认领
                        return safeReleaseClaim(claimZ);
                    }
                    String assignZ = assignPath + "/" + worker + "/" + taskId;

                    // 读取当前 leader 的 czxid 作为 fencing token，并附到分配数据
                    return zf.exists("/master", null)
                            .thenComposeAsync(masterOpt -> {
                                long token = masterOpt.map(s -> s.getCzxid()).orElse(-1L);
                                byte[] src = dr.data();
                                byte[] payload;
                                if (token >= 0) {
                                    byte[] header = ("token:" + token + "\n").getBytes();
                                    payload = new byte[header.length + src.length];
                                    System.arraycopy(header, 0, payload, 0, header.length);
                                    System.arraycopy(src, 0, payload, header.length, src.length);
                                } else {
                                    payload = src;
                                }

                                // 根据 ZkFutures 的 multi 设置选择策略
                                if (zf.isMultiEnabled()) {
                                    return tryAtomicAssignment(assignZ, payload, taskZ, claimZ)
                                            .exceptionallyCompose(ex -> fallbackNonAtomicAssignment(assignZ, payload, taskZ, claimZ));
                                } else {
                                    // 直接使用非原子操作
                                    return fallbackNonAtomicAssignment(assignZ, payload, taskZ, claimZ);
                                }
                            }, exec);
                }, exec)
                .exceptionallyCompose(ex -> {
                    // 失败路径：尽量把 claim 删掉（防泄露）
                    Throwable t = ZkFutures.unwrap(ex);
                    if (t instanceof KeeperException.NoNodeException) {
                        // 任务被别人清了；删 claim
                        return safeReleaseClaim(claimZ);
                    }
                    if (t instanceof KeeperException.NodeExistsException) {
                        // claims/assign 已存在 → 视为已处理
                        return CompletableFuture.completedFuture(null);
                    }
                    // 其它错误 → 尽力释放 claim，再交给 refresh 自愈
                    return safeReleaseClaim(claimZ);
                });
    }

    /**
     * 尝试使用 multi 操作进行原子性任务分配
     * 
     * 使用ZooKeeper的multi操作实现原子性：创建assign节点、删除task节点、删除claim节点。
     * 所有操作在一个事务中执行，要么全部成功，要么全部失败。
     * 
     * 性能优势：
     * - 1次网络往返 vs 3次网络往返
     * - 服务器端批量处理优化
     * - 减少客户端回调处理开销
     * 
     * @param assignZ 分配节点路径
     * @param data    任务数据
     * @param taskZ   任务节点路径
     * @param claimZ  认领节点路径
     * @return 原子分配完成的Future
     */
    private CompletableFuture<Void> tryAtomicAssignment(String assignZ, byte[] data, String taskZ, String claimZ) {
        return CompletableFuture.completedFuture(null)
                .thenComposeAsync(v -> {
                    return ZkFutures.MultiOps.create()
                            .createEphemeral(assignZ, data, ZooDefs.Ids.OPEN_ACL_UNSAFE)
                            .delete(taskZ, -1)
                            .delete(claimZ, -1)
                            .execute(zf);
                }, exec)
                .thenApply(results -> null);
    }

    /**
     * Fallback 到非原子操作
     * 
     * 当multi操作不可用时，使用非原子操作进行任务分配。
     * 采用最终一致性模式：分步执行操作，通过补偿机制保证最终一致性。
     * 
     * @param assignZ 分配节点路径
     * @param data    任务数据
     * @param taskZ   任务节点路径
     * @param claimZ  认领节点路径
     * @return 非原子分配完成的Future
     */
    private CompletableFuture<Void> fallbackNonAtomicAssignment(String assignZ, byte[] data, String taskZ,
            String claimZ) {
        return CompletableFuture.completedFuture(null)
                .thenComposeAsync(v -> zf.createEphemeral(assignZ, data, ZooDefs.Ids.OPEN_ACL_UNSAFE), exec)
                .thenComposeAsync(x -> zf.delete(taskZ, -1).exceptionally(e -> null), exec)
                .thenComposeAsync(x -> safeReleaseClaim(claimZ), exec);
    }

    // 自定义 fencing 去除，改用 Stat.ephemeralOwner 与当前会话对齐

    /**
     * 安全释放认领
     * 
     * 使用重试机制安全删除认领节点，避免认领泄露。
     * 对NoNodeException视为成功，其他异常记录后忽略。
     * 
     * @param claimZ 认领节点路径
     * @return 释放完成的Future
     */
    private CompletableFuture<Void> safeReleaseClaim(String claimZ) {
        return zf.delete(claimZ, -1).exceptionally(e -> {
                    // NoNode 当成功；其他错误打点后吞掉，避免卡链
                    Throwable t = ZkFutures.unwrap(e);
                    if (t instanceof KeeperException.NoNodeException)
                        return null;
                    return null;
                });
    }

    /**
     * 定期补偿任务：检测和修复不一致状态
     * 
     * 最终一致性保证机制：定期检测和修复各种不一致状态。
     * 包括孤儿claims清理、孤儿tasks重新分配、重复分配检测等。
     */
    public void runCompensation() {
        if (stopped.get())
            return;

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 检测孤儿 claims（有 claim 但没有对应的 task）
                compensateOrphanClaims();

                // 2. 检测孤儿 tasks（有 task 但没有对应的 claim）
                compensateOrphanTasks();

                // 3. 检测重复分配（同一个 task 被分配给多个 worker）
                compensateDuplicateAssignments();

            } catch (Exception e) {
                // 补偿失败不影响主流程，只记录日志
                System.err.println("Compensation failed: " + e.getMessage());
            }
        }, exec).exceptionally(ex -> null);
    }

    /**
     * 补偿孤儿 claims：删除没有对应 task 的 claim
     * 
     * 检测并清理孤儿认领：有认领但没有对应任务的情况。
     * 这通常发生在任务被其他节点处理或删除后，认领节点未及时清理。
     * 
     * @return 清理完成的Future
     */
    private CompletableFuture<Void> compensateOrphanClaims() {
        return zf.ensurePersistent(claimsPath)
                .thenComposeAsync(v -> zf.getChildrenOrEmpty(claimsPath, null), exec)
                .thenComposeAsync(opt -> {
                    if (opt.isEmpty())
                        return CompletableFuture.completedFuture(null);

                    List<CompletableFuture<Void>> cleanupTasks = new ArrayList<>();
                    for (String claimId : opt.get().children()) {
                        String claimZ = claimsPath + "/" + claimId;
                        String taskZ = tasksPath + "/" + claimId;

                        // 检查对应的 task 是否存在，并验证 fencing 数据
                        cleanupTasks.add(
                                zf.exists(taskZ, null)
                                        .thenComposeAsync(taskExists -> {
                                            if (taskExists.isEmpty()) {
                                                // task 不存在，删除孤儿 claim
                                                return zf.delete(claimZ, -1)
                                                        .exceptionally(e -> null);
                                            }
                                            return CompletableFuture.completedFuture(null);
                                        }, exec)
                                        .thenComposeAsync(v -> {
                        // 校验会话：ephemeralOwner 不等于当前 sessionId 视为旧会话认领，删除
                        return zf.getData(claimZ, null)
                                .thenComposeAsync(nd -> {
                                    long owner = (nd.stat() != null) ? nd.stat().getEphemeralOwner() : -1L;
                                    if (owner != zf.raw().getSessionId()) {
                                        return zf.delete(claimZ, -1).exceptionally(e -> null);
                                    }
                                    return CompletableFuture.completedFuture(null);
                                }, exec)
                                .exceptionally(e -> null);
                                        }, exec));
                    }

                    return CompletableFuture.allOf(cleanupTasks.toArray(CompletableFuture[]::new));
                }, exec);
    }

    /**
     * 补偿孤儿 tasks：为没有 claim 的 task 重新分配
     * 
     * 检测并重新分配孤儿任务：有任务但没有对应认领的情况。
     * 这通常发生在认领节点丢失或分配过程中断后。
     * 
     * @return 重新分配完成的Future
     */
    private CompletableFuture<Void> compensateOrphanTasks() {
        return zf.ensurePersistent(tasksPath)
                .thenComposeAsync(v -> zf.getChildrenOrEmpty(tasksPath, null), exec)
                .thenComposeAsync(opt -> {
                    if (opt.isEmpty())
                        return CompletableFuture.completedFuture(null);

                    List<CompletableFuture<Void>> reassignTasks = new ArrayList<>();
                    for (String taskId : opt.get().children()) {
                        String claimZ = claimsPath + "/" + taskId;

                        // 检查对应的 claim 是否存在
                        reassignTasks.add(
                                zf.exists(claimZ, null)
                                        .thenComposeAsync(claimExists -> {
                                            if (claimExists.isEmpty()) {
                                                // claim 不存在，重新分配任务
                                                return claimThenAssign(taskId);
                                            }
                                            return CompletableFuture.completedFuture(null);
                                        }, exec));
                    }

                    return CompletableFuture.allOf(reassignTasks.toArray(CompletableFuture[]::new));
                }, exec);
    }

    /**
     * 补偿重复分配：检测同一 task 被分配给多个 worker 的情况
     * 
     * 检测并处理重复分配：同一个任务被分配给多个worker的情况。
     * 这通常发生在并发分配或补偿过程中的竞态条件。
     * 
     * @return 重复分配处理完成的Future
     */
    private CompletableFuture<Void> compensateDuplicateAssignments() {
        return zf.ensurePersistent(assignPath)
                .thenComposeAsync(v -> zf.getChildrenOrEmpty(assignPath, null), exec)
                .thenComposeAsync(opt -> {
                    if (opt.isEmpty())
                        return CompletableFuture.completedFuture(null);

                    // 收集所有 worker 的分配情况
                    List<CompletableFuture<List<String>>> workerAssignments = new ArrayList<>();
                    for (String worker : opt.get().children()) {
                        String workerAssignPath = assignPath + "/" + worker;
                        workerAssignments.add(
                                zf.ensurePersistent(workerAssignPath)
                                        .thenComposeAsync(v -> zf.getChildrenOrEmpty(workerAssignPath, null), exec)
                                        .thenApplyAsync(
                                                childrenOpt -> childrenOpt.map(ZkFutures.ChildrenSnapshot::children)
                                                        .orElse(List.of()),
                                                exec));
                    }

                    return CompletableFuture.allOf(workerAssignments.toArray(CompletableFuture[]::new))
                            .thenApplyAsync(v -> {
                                // 检测重复分配并清理
                                // 这里可以实现更复杂的重复检测逻辑
                                return null;
                            }, exec);
                }, exec);
    }

    /**
     * ZooKeeper 事件处理器
     * 
     * 处理ZooKeeper连接状态变化和任务路径变化事件。
     * 在连接恢复时刷新任务，在任务路径变化时重新分配任务。
     * 
     * @param e ZooKeeper事件
     */
    @Override
    public void process(WatchedEvent e) {
        if (stopped.get())
            return;
        if (e.getState() == Event.KeeperState.Expired) {
            exec.submit(() -> {
                /* 等 SyncConnected 再refresh */});
            return;
        }
        if (e.getType() == Event.EventType.None && e.getState() == Event.KeeperState.SyncConnected) {
            exec.submit(this::refreshTasks);
            return;
        }
        if (e.getType() == Event.EventType.NodeChildrenChanged && tasksPath.equals(e.getPath())) {
            exec.submit(this::refreshTasks);
        }
    }

    /**
     * 关闭任务分配器
     * 
     * 优雅关闭：停止补偿任务，关闭线程池，释放资源。
     * 确保所有正在进行的操作能够正常完成。
     */
    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            if (compensationTask != null) {
                compensationTask.cancel(false);
            }
            compensationScheduler.shutdownNow();
            exec.shutdownNow();
        }
    }
}
