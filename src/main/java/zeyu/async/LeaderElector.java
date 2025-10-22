package zeyu.async;

import org.apache.zookeeper.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LeaderElector
 *
 * 职责：
 * - 在给定路径（/master）上做“主从选举”：创建 EPHEMERAL 节点即当选；
 * - 若节点已存在，则只对该路径挂“一次性 watch”，等待主节点删除后再尝试；
 * - 任何异常/重连都通过统一入口（checkThenDecide）自愈；
 * - 所有推进放在单线程执行器（exec）上，保证时序和避免竞态。
 *
 * 关键点：
 * 1) 统一入口：checkThenDecide()
 *    - 先 exists(MASTER, null) 查看事实：空 → tryBecomeLeader()；非空 → watchLeader()
 * 2) 一次性 watch：每次 watchLeader() 都用 exists(MASTER, this) 挂下一次 watch（NodeDeleted）
 * 3) 自愈闭环：异常/重连/竞态，一律回到 checkThenDecide() 兜底
 * 4) 回调隔离：onElected/onExpired 在 exec 线程执行并 try/catch，避免业务异常破坏链路
 */
public class LeaderElector implements Watcher, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LeaderElector.class.getName());

    /** 选举用的 master 节点路径（EPHEMERAL） */
    private static final String MASTER = "/master";

    private final ZkFutures zf;
    private final String serverId;

    /** 单线程执行器：所有推进都投递到这里，确保串行（避免并发竞态） */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "election"); t.setDaemon(true); return t;
    });

    /** 关闭标志：关闭后快速短路所有推进 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 当选回调：create 成功后在 exec 上触发（已用 try/catch 保护） */
    private final Consumer<String> onElected;

    /** 会话过期回调（可选）：仅通知；真正重连后由 SyncConnected 分支触发自愈 */
    private Consumer<Event.KeeperState> onExpired;

    public LeaderElector(ZkFutures zf, String serverId,
                         Consumer<String> onElected,
                         Consumer<Event.KeeperState> onExpired) {
        this.zf = zf;
        this.serverId = serverId;
        this.onElected = onElected;
        this.onExpired = onExpired;
    }

    public LeaderElector(ZkFutures zf, String serverId,
                         Consumer<String> onElected) {
        this.zf = zf;
        this.serverId = serverId;
        this.onElected = onElected;
    }

    /** 启动：永远从“自检入口”开始（幂等） */
    public CompletableFuture<Void> start() { return checkThenDecide(); }

    /**
     * 统一入口（幂等自愈）：
     * - 看事实：exists(MASTER, null)；
     * - 空 → 尝试创建（tryBecomeLeader）；
     * - 非空 → 对现有 master 挂一次性 watch（watchLeader）。
     * - 任意异常走 onFailGoSelfHeal() 回到本入口。
     */
    private CompletableFuture<Void> checkThenDecide() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        return zf.exists(MASTER, null)
                .thenComposeAsync(opt -> {
                    if (stopped.get()) return CompletableFuture.completedFuture(null);
                    return (opt.isEmpty()) ? tryBecomeLeader() : watchLeader();
                }, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /**
     * 抢主：
     * - 尝试 create EPHEMERAL 节点 MASTER，成功即当选；
     * - 成功后在 exec 上触发 onElected（有 try/catch 防护，避免业务异常影响链路）；
     * - 失败（NodeExists/连接抖动等）统一回 onFailGoSelfHeal()。
     *
     * 注意：
     * - 这里没有做“只触发一次”的幂等护栏；若你担心极端情况下多次回调，可加 AtomicBoolean 控制只触发一次。
     */
    private CompletableFuture<Void> tryBecomeLeader() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        byte[] data = serverId.getBytes(StandardCharsets.UTF_8);
        return zf.createEphemeral(MASTER, data, ZooDefs.Ids.OPEN_ACL_UNSAFE)
                .thenRunAsync(() -> {
                    try {
                        onElected.accept(serverId);
                    } catch (Throwable t) {
                        LOG.log(Level.WARNING, "onChanged threw", t);
                    }}, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /**
     * 监听当前 leader：
     * - 用 exists(MASTER, this) 对 master 节点挂“一次性 watch”（NodeDeleted）；
     * - 如果竞态下节点已不存在（opt.empty），立即回到统一入口（checkThenDecide）抢主；
     * - 如果存在，则只设表不推进：等待 NodeDeleted 事件到来。
     */
    private CompletableFuture<Void> watchLeader() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);

        return zf.exists(MASTER, this)
                .thenComposeAsync(opt -> {
                    if (stopped.get()) return CompletableFuture.completedFuture(null);
                    if (opt.isEmpty()) {
                        // 竞态：在挂表前 master 已被删除 → 立刻回入口，尝试抢主
                        return checkThenDecide();
                    }
                    // 成功对现有 master 挂了一次性 watch：不再推进，等回调触发
                    return CompletableFuture.completedFuture(null);
                }, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /**
     * 自愈路径：
     * - 任何异常都切回 exec 再进 checkThenDecide()，保证“再看事实 + 重新设表”；
     * - stopped 时短路返回。
     * - 可选：这里可以加 50~200ms 随机退避，避免全体同时轰击 ZK（工程化建议）。
     */
    private CompletableFuture<Void> onFailGoSelfHeal() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> null, exec)
                .thenCompose(v -> checkThenDecide());
    }

    /**
     * Watcher 回调（来自 ZK 事件线程）：
     * - 不做重活，只分发到 exec，保证串行推进；
     * - 关心 3 类事件：
     *   1) Expired：仅通知上层（onExpired），不立即自愈，等待新会话建立（None+SyncConnected）再 check；
     *   2) NodeDeleted：master 被删 → 回到统一入口（checkThenDecide）；
     *   3) None+SyncConnected：重连成功 → 回到统一入口自检（checkThenDecide）。
     * - 其他事件：当前实现选择继续 watchLeader（可以视需求收紧为忽略）。
     *
     * 注：日志 “Session timed out” 更准确的表述应为 “ZooKeeper session expired”。
     */
    @Override
    public void process(final WatchedEvent e) {
        if (stopped.get()) return;
        if (e.getState() == Event.KeeperState.Expired) {
            exec.submit(() -> {
                LOG.warning("Session timed out"); // 建议文案：ZooKeeper session expired
                if (onExpired != null) {
                    try {
                        onExpired.accept(e.getState());
                    } catch (Throwable ex) {
                        LOG.log(Level.WARNING, "Exception while trying to become leader", ex);
                    }
                }
                // 注意：不在这里直接 checkThenDecide；等到新的 SyncConnected 后再执行
            });
            return;
        }
        if (e.getType() == Event.EventType.NodeDeleted && MASTER.equals(e.getPath())) {
            // 当前 master 节点删除 → 回到入口重新竞争/监听
            exec.submit(this::checkThenDecide);
            return;
        }
        if (e.getType() == Event.EventType.None && e.getState() == Event.KeeperState.SyncConnected) {
            // 重连成功（可能错过事件）→ 回到入口自检
            exec.submit(this::checkThenDecide);
            return;
        }
        // 其他事件：维持监听（可按需改为忽略以减少调用）
        exec.submit(this::watchLeader);
    }

    /**
     * 关闭：
     * - 标记 stopped，阻止后续推进；
     * - 关闭内部单线程执行器（若未来改为外部注入线程池，这里就不要关闭）。
     */
    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) exec.shutdownNow();
    }
}
