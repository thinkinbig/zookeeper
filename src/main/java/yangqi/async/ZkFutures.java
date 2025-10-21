package yangqi.async;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class ZkFutures implements AutoCloseable{
    private final ZooKeeper zk;
    private final ScheduledExecutorService  scheduler;

    public ZkFutures(String connectString, int sessionTimeoutMs,
                     Watcher defaultWatcher, ScheduledExecutorService scheduler) throws IOException {
        this.zk = new ZooKeeper(connectString, sessionTimeoutMs, defaultWatcher);
        this.scheduler = Objects.requireNonNullElseGet(
                scheduler, () -> Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, "zkfutures-scheduler");
                    t.setDaemon(true);
                    return t;
                })
        );
    }

    public ZooKeeper raw() { return zk; }

    public ScheduledExecutorService scheduler() { return scheduler; }

    public CompletableFuture<String> createEphemeral(String path, byte[] data,
                                                     List<ACL>acl) {
        CompletableFuture<String> cf = new CompletableFuture<>();
        zk.create(path, data, acl, CreateMode.EPHEMERAL, (rc, p, ctx, name) -> completeByCode(cf, rc, name), null);
        return cf;
    }

    public CompletableFuture<String> createEphemeralSequential(String path, byte[] data, List<ACL> acl) {
        CompletableFuture<String> cf = new CompletableFuture<>();
        zk.create(path, data, acl, CreateMode.EPHEMERAL_SEQUENTIAL, (rc, p, ctx, name) -> completeByCode(cf, rc, name), null);
        return cf;
    }

    public CompletableFuture<Stat> exists(String path, Watcher watcher) {
        CompletableFuture<Stat> cf = new CompletableFuture<>();
        zk.exists(path, watcher, (rc, p, ctx, stat) -> completeByCode(cf, rc, stat), null);
        return cf;
    }

    public CompletableFuture<Void> delete(String path, int version) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        zk.delete(path, version, (rc, p, ctx) -> completeByCode(cf, rc, null), null);
        return cf;
    }

    private static <T> void completeByCode(CompletableFuture<T> cf, int rc,  T okVal) {
        KeeperException.Code code = KeeperException.Code.get(rc);
        if (Objects.requireNonNull(code) == KeeperException.Code.OK) {
            cf.complete(okVal);
        } else {
            cf.completeExceptionally(KeeperException.create(code));
        }
    }

    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> cf, Duration d, ScheduledExecutorService sch) {
        final CompletableFuture<T> timeout = new CompletableFuture<>();
        ScheduledFuture<?> task = sch.schedule(() -> timeout.completeExceptionally(new TimeoutException()), d.toMillis(), TimeUnit.MILLISECONDS);

        cf.whenComplete((r, t) -> task.cancel(false));
        return cf.applyToEither(timeout, x->x);
    }

    public static <T> CompletableFuture<T> retryAsync(Supplier<CompletableFuture<T>> op,
                                                      int maxRetires,
                                                      Duration baseBackoff,
                                                      ScheduledExecutorService sch,
                                                      Class<?>... retryOn) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        attempt(op, 0, maxRetires, baseBackoff, sch, cf, retryOn);
        return cf;
    }

    private static <T> void attempt(Supplier<CompletableFuture<T>> op, int n, int max,
                                    Duration baseBackoff, ScheduledExecutorService sch,
                                    CompletableFuture<T> sink, Class<?>[] retryOn) {
        op.get().whenComplete((v,e) -> {
            if (e == null) {
                sink.complete(v);
            } else if (n < max && shouldRetry(e, retryOn)) {
                long delay = (long) (baseBackoff.toMillis() * Math.pow(2, n) * (0.5 + ThreadLocalRandom.current().nextDouble()));
                sch.schedule(() -> attempt(op, n+1, max, baseBackoff, sch, sink, retryOn), delay, TimeUnit.MILLISECONDS);
            } else {
                sink.completeExceptionally(e);
            }
        });
    }

    private static boolean shouldRetry(Throwable e, Class<?>[] retryOn) {
        Throwable cause = unwrap(e);
        for (Class<?> retry : retryOn) {
            if (retry.isAssignableFrom(cause.getClass())) {
                return true;
            }
        }

        if (cause instanceof KeeperException.ConnectionLossException) { return true; }
        return cause instanceof KeeperException.OperationTimeoutException;
    }


    static Throwable unwrap(Throwable e) {
        if (e instanceof CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }

        return e;
    }

    @Override
    public void close() throws Exception {
        zk.close();
    }
}
