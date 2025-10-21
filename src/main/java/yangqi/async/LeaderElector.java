package yangqi.async;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static yangqi.async.ZkFutures.unwrap;


public class LeaderElector implements Watcher, AutoCloseable {

    private static final String MASTER = "/leader/master";
    private final ZkFutures zf;
    private final String serverId;
    private final ExecutorService electExec =
            Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"election");
                t.setDaemon(true); return t; });
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public LeaderElector(ZkFutures zf, String serverId) {
        this.zf = zf;
        this.serverId = serverId;
    }

    public CompletableFuture<Void> start() {
        return tryBecomeLeader();
    }

    private CompletableFuture<Void> tryBecomeLeader() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        byte[] payload = serverId.getBytes(StandardCharsets.UTF_8);
        return zf.createEphemeral(MASTER, payload, ZooDefs.Ids.OPEN_ACL_UNSAFE)
                .thenAcceptAsync(path->onElected(),  electExec)
                .exceptionallyCompose(ex -> {
                    Throwable t = unwrap(ex);
                    if (t instanceof KeeperException.NodeExistsException) {
                        return watchLeader();
                    } else if (t instanceof KeeperException.ConnectionLossException ||
                    t instanceof  KeeperException.OperationTimeoutException) {
                        return checkThenDecide();
                    } else if (t instanceof KeeperException.NoAuthException) {
                        return failed(ex);
                    }
                    return failed(ex);
                });
    }

    private CompletableFuture<Void> watchLeader() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        return zf.exists(MASTER, this).thenAcceptAsync(stat -> {},  electExec);
    }

    private CompletableFuture<Void> checkThenDecide() {
        if (stopped.get()) return CompletableFuture.completedFuture(null);
        return zf.exists(MASTER, null).thenComposeAsync(stat -> (stat == null) ?
                tryBecomeLeader(): watchLeader(),  electExec);
    }

    private void onElected() {
        Logger.getLogger(LeaderElector.class.getName()).info("Elected leader");
    }

    private void onExpired() {
        Logger.getLogger(LeaderElector.class.getName()).info("Session has been expired");
    }


    @Override
    public void close() throws Exception {
        if (stopped.compareAndSet(false, true)) {
            electExec.shutdown();
        }
    }

    @Override
    public void process(WatchedEvent watchedEvent) {
        if (stopped.get()) return;
        if (watchedEvent.getType() == Event.EventType.NodeDeleted && MASTER.equals(watchedEvent.getPath())) {
            tryBecomeLeader();
        } else if (watchedEvent.getState() == Event.KeeperState.Expired) {
            // connection state changed
            onExpired();
        } else if (watchedEvent.getType() == Event.EventType.None) {
            // do nothing
        } else {
            watchLeader();
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(t);
        return future;
    }


}
