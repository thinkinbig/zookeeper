package zeyu.async.server;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import zeyu.async.common.ZkFutures;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
 
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WorkersWatcher
 *
 * 作用：主节点侧持续监控某个父路径（如 /workers）的子节点列表变化。
 * 设计要点：
 * 1) 每次通过 getChildren(path, this) 获取“全量快照”并同时挂上“一次性 watch”。
 * 2) 所有推进都在单线程执行器（exec）上串行执行，避免竞态。
 * 3) 任何失败（连接丢失/超时等）通过统一入口 refreshWorkers() “自愈”再来一次。
 * 4) 回调 onChanged 同时给出 Snapshot（全量）与 Diff（相对上一次的增量）。
 * 5) 会话过期时仅通知上层，等待新会话（SyncConnected）后再 refresh。
 */
public class WorkersWatcher implements Watcher, AutoCloseable {

    /** 对应某一时刻 /workers 的“真实全集” + 便于快速短路的 cversion + 取快照时间 */
    public record Snapshot(Set<String> children, int cversion, Instant ts) {
    }

    /** 相对上一帧的增量（added/removed），并携带当前帧的 snapshot */
    public record Diff(Snapshot snapshot, Set<String> added, Set<String> removed) {
    }

    private final String workersPath; // 例如：/workers
    private final ZkFutures zf;

    /** 单线程执行器：把所有推进投递到这条队列，保证串行语义与可预期顺序 */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "workersWatcher");
        thread.setDaemon(true);
        return thread;
    });

    private static final Logger LOG = Logger.getLogger(WorkersWatcher.class.getName());

    private static final ZkFutures.ChildrenSnapshot EMPTY_CHILDREN_SNAPSHOT = new ZkFutures.ChildrenSnapshot(
            java.util.Collections.emptyList(), null);

    /** 最近一次成功获取的快照；用于计算增量（初始为空集） */
    private Snapshot last = new Snapshot(Set.of(), -1, Instant.EPOCH);

    /** 变化回调：上层可在此幂等覆盖当前内存状态（用 snapshot），并对 removed 做重分配等操作（用 diff） */
    private final BiConsumer<Snapshot, Diff> onChanged;

    /** 会话过期回调（可选）：仅通知，实际重建/重启由上层掌控 */
    private Consumer<Event.KeeperState> onExpired;

    /** 关闭标志：确保关闭后不再推进、不中断退出过程 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public WorkersWatcher(String workersPath, ZkFutures zf,
            BiConsumer<Snapshot, Diff> onChanged,
            Consumer<Event.KeeperState> onExpired) {
        this.workersPath = workersPath;
        this.zf = zf;
        this.onChanged = onChanged;
        this.onExpired = onExpired;
    }

    public WorkersWatcher(String workersPath, ZkFutures zf,
            BiConsumer<Snapshot, Diff> onChanged) {
        this.workersPath = workersPath;
        this.zf = zf;
        this.onChanged = onChanged;
    }

    /** 启动：读取一次“事实快照”并挂上“一次性 watch”；后续靠事件或自愈再次进入 */
    public CompletableFuture<Void> start() {
        return zf.ensurePersistent(workersPath)
                .thenComposeAsync(v -> refreshWorkers(), exec)
                .exceptionally(e -> null);
    }

    /**
     * 统一入口（自愈/刷新）：
     * - 确保路径存在后调用 zf.getChildren(path, this) 获取当前全集，并为"下一次变化"挂上一次性 watch。
     * - 构造 Snapshot，基于上一次快照计算增量 Diff。
     * - 始终回调 onChanged（快照版语义：上层随时可拿到当前全集）。
     * - 任意异常走 onFailGoSelfHeal() 回到本入口，重新设表与获取快照。
     */
    private CompletableFuture<Void> refreshWorkers() {
        if (stopped.get()) {
            return CompletableFuture.completedFuture(null);
        }

        return zf.ensurePersistent(workersPath)
                .thenComposeAsync(v -> zf.getChildrenOrEmpty(workersPath, this), exec)
                .thenAcceptAsync(op -> {
                    if (stopped.get()) {
                        return;
                    }

                    // 父路径不存在（NONODE） 时不再触发异常/自愈风暴，而是自然视为“当前 worker 集为空”，首帧也会回调一次，贴合“快照版”语义s
                    var cs = op.orElse(EMPTY_CHILDREN_SNAPSHOT);
                    // 构造当前帧快照
                    Snapshot curr = snapshotFrom(cs);
                    // 基于上一帧计算增量
                    Diff diff = diff(last, curr);
                    // 更新基线
                    last = curr;

                    // 回调上层（建议幂等覆盖：用 curr.children() 直接替换内存中的当前 worker 集；
                    // 同时用 diff.removed() 做重分配、用 diff.added() 做均衡等）
                    try {
                        onChanged.accept(curr, diff); // 仍在 exec 单线程内执行，保证顺序
                    } catch (Throwable t) {
                        // 业务回调异常不应影响自愈链；在此拦截记录
                        LOG.log(Level.WARNING, "onChanged threw", t);
                    }
                }, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /**
     * 自愈路径：
     * - 任何异常（如 ConnectionLoss/OperationTimeout）都切回 exec，再次进入 refreshWorkers()
     * - 这样保证“再读事实 + 重新设表”，避免失明。
     */
    private CompletableFuture<Void> onFailGoSelfHeal() {
        if (stopped.get())
            return CompletableFuture.completedFuture(null);

        return CompletableFuture.completedFuture(null)
                .thenComposeAsync(v -> stopped.get() ? CompletableFuture.completedFuture(null)
                        : refreshWorkers(), exec)
                .exceptionally(e -> null);
    }

    /** 从 ZkFutures 的 ChildrenSnapshot 构造不可变快照，并带上获取时间 */
    private static Snapshot snapshotFrom(ZkFutures.ChildrenSnapshot cs) {
        int cv = (cs.stat() != null) ? cs.stat().getCversion() : -1; // cversion: 子节点版本号
        return new Snapshot(Set.copyOf(cs.children()), cv, Instant.now());
    }

    /** 计算相对上一帧的增量（added/removed），并返回携带当前帧的 Diff */
    private static Diff diff(Snapshot oldS, Snapshot newS) {
        Set<String> added = new HashSet<>(newS.children());
        added.removeAll(oldS.children());

        Set<String> removed = new HashSet<>(oldS.children());
        removed.removeAll(newS.children());

        return new Diff(newS, Set.copyOf(added), Set.copyOf(removed));
    }

    /**
     * 关闭：
     * - 标记 stopped，阻止新的推进。
     * - 关闭内部单线程执行器（若未来改为外部注入的共享线程池，则不要在这里关闭）。
     */
    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            exec.shutdownNow();
        }
    }

    /**
     * Watcher 回调（来自 ZK 事件线程）：
     * - 不做重活，只做分发，把推进投递回 exec 单线程，保证串行与可控顺序。
     * - 处理三类关键事件：
     * 1) Expired：仅通知上层，等待新会话（SyncConnected）后再 refresh。
     * 2) None+SyncConnected：重连成功，自检一次（refresh）。
     * 3) NodeChildrenChanged：子节点变化，刷新快照并重新挂表（refresh）。
     */
    @Override
    public void process(WatchedEvent watchedEvent) {
        if (stopped.get()) {
            return;
        }

        if (watchedEvent.getState() == Event.KeeperState.Expired) {
            exec.submit(() -> {
                LOG.warning("WorkersWatcher: session expired; will re-arm after reconnect");
                if (onExpired != null) {
                    try {
                        onExpired.accept(watchedEvent.getState());
                    } catch (Throwable t) {
                        LOG.warning("onExpired threw: " + t);
                    }
                }
                // 注意：不立即 refresh，等新会话建立（None+SyncConnected）后再进入
            });
        } else if (watchedEvent.getType() == Event.EventType.NodeChildrenChanged
                && workersPath.equals(watchedEvent.getPath())) {
            // 子节点集合发生变化：再读一次事实 + 重新挂一次性 watch
            exec.submit(this::refreshWorkers);

        } else if (watchedEvent.getState() == Event.KeeperState.SyncConnected
                && watchedEvent.getType() == Event.EventType.None) {
            // 重连成功：可能错过事件，主动自检
            exec.submit(this::refreshWorkers);
        }
        // 其他事件：忽略（保持纯粹：只响应与“子节点变化/会话状态”相关的事件）
    }
}
