package yangqi.async;

import org.apache.zookeeper.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class LeaderElector implements Watcher, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LeaderElector.class.getName());
    private static final String MASTER = "/leader/master";

    private final ZkFutures zf;
    private final String serverId;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "election"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public LeaderElector(ZkFutures zf, String serverId) {
        this.zf = zf;
        this.serverId = serverId;
    }

    /** 启动：永远从自检入口开始 */
    public CompletableFuture<Void> start() { return checkThenDecide(); }

    /** 统一入口：看事实→决定抢/监听 */
    private CompletableFuture<Void> checkThenDecide() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        return zf.exists(MASTER, null)
                .thenComposeAsync(stat -> {
                    if (stopped.get()) return CompletableFuture.completedFuture(null);
                    return (stat == null) ? tryBecomeLeader() : watchLeader();
                }, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /** 抢主：create 成功就当选；常见异常都回自愈 */
    private CompletableFuture<Void> tryBecomeLeader() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        byte[] data = serverId.getBytes(StandardCharsets.UTF_8);
        return zf.createEphemeral(MASTER, data, ZooDefs.Ids.OPEN_ACL_UNSAFE)
                .thenRunAsync(this::onElected, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /** 监听当前 leader 的删除；只设表，不决策 */
    private CompletableFuture<Void> watchLeader() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        return zf.exists(MASTER, this)
                .thenAcceptAsync(stat -> { /* no-op：等回调 */ }, exec)
                .exceptionallyCompose(ex -> onFailGoSelfHeal());
    }

    /** 失败统一走：切回选举线程 → 再自检一次 */
    private CompletableFuture<Void> onFailGoSelfHeal() {
        return CompletableFuture.supplyAsync(() -> null, exec)
                .thenCompose(v -> checkThenDecide());
    }

    /** 事件回调：全部丢回选举线程处理 */
    @Override public void process(WatchedEvent e) {
        if (stopped.get()) return;
        if (e.getState() == Event.KeeperState.Expired) {
            exec.submit(this::onExpired);  // 等新会话来了再 start()/checkThenDecide()
            return;
        }
        if (e.getType() == Event.EventType.NodeDeleted && MASTER.equals(e.getPath())) {
            exec.submit(this::checkThenDecide);
            return;
        }
        if (e.getType() == Event.EventType.None && e.getState() == Event.KeeperState.SyncConnected) {
            exec.submit(this::checkThenDecide);
            return;
        }
        exec.submit(this::watchLeader); // 其他事件保持监听
    }

    /** 当选&过期：你按需实现 */
    private void onElected() { LOG.info("Elected leader by " + serverId); }
    private void onExpired() { LOG.info("Session expired; wait for new session, then restart"); }

    @Override public void close() {
        if (stopped.compareAndSet(false, true)) exec.shutdownNow();
    }
}
