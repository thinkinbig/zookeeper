package zeyu.async.client;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.OperationTimeoutException;

import zeyu.async.common.ZkFutures;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TaskResultWatcher - 客户端等待任务结果的轻量工具
 */
public class TaskResultWatcher implements Watcher, AutoCloseable {

    private final ZkFutures zf;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "taskResultWatcher");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public record Pending(CompletableFuture<Optional<String>> future,
                          Consumer<ZkFutures.NodeData> onUpdate) {
    }

    /** 状态根路径, 例如 "/status" */
    private final String statusPath; 

    private final ConcurrentHashMap<String, Pending> pendings = new ConcurrentHashMap<>();

    public TaskResultWatcher(ZkFutures zf, String statusPath) {
        this.zf = zf;
        this.statusPath = statusPath;
    }

    public CompletableFuture<Optional<String>> await(String statusZnode, Consumer<ZkFutures.NodeData> onUpdate) {
        if (stopped.get()) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<String>> cf = new CompletableFuture<>();
        Pending p = new Pending(cf, onUpdate);
        pendings.put(statusZnode, p);
        rearmAndFetch(statusZnode);
        return cf;
    }

    public CompletableFuture<Void> start() {
        if (statusPath == null || statusPath.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return zf.ensurePersistent(statusPath)
                .thenComposeAsync(v -> {
                    for (String path : pendings.keySet()) {
                        exec.submit(() -> rearmAndFetch(path));
                    }
                    return CompletableFuture.completedFuture(null);
                }, exec);
    }

    @Override
    public void process(WatchedEvent event) {
        if (stopped.get()) return;

        if (event.getState() == Event.KeeperState.Expired) {
            return;
        }
        if (event.getType() == Event.EventType.None && event.getState() == Event.KeeperState.SyncConnected) {
            for (String path : pendings.keySet()) {
                exec.submit(() -> rearmAndFetch(path));
            }
            return;
        }
        if (event.getType() == Event.EventType.NodeCreated || event.getType() == Event.EventType.NodeDataChanged) {
            String path = event.getPath();
            if (path != null && pendings.containsKey(path)) {
                exec.submit(() -> rearmAndFetch(path));
            }
        }
    }

    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            exec.shutdownNow();
        }
    }

    private void rearmAndFetch(String path) {
        if (stopped.get()) return;
        Pending pending = pendings.get(path);
        if (pending == null) return;
        CompletableFuture<Optional<String>> sink = pending.future();
        if (sink.isDone()) return;

        ZkFutures.retryAsync(
                () -> zf.exists(path, this),
                3,
                Duration.ofMillis(100),
                zf.scheduler(),
                ConnectionLossException.class,
                OperationTimeoutException.class)
                .thenComposeAsync(opt -> {
                    if (stopped.get() || sink.isDone()) return CompletableFuture.completedFuture(null);
                    if (opt.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return ZkFutures.retryAsync(
                            () -> zf.getData(path, this),
                            3,
                            Duration.ofMillis(100),
                            zf.scheduler(),
                            ConnectionLossException.class,
                            OperationTimeoutException.class)
                            .thenAcceptAsync(nd -> {
                                if (!sink.isDone()) {
                                    var cb = pending.onUpdate();
                                    if (cb != null) cb.accept(nd);
                                    sink.complete(Optional.of(new String(nd.data(), StandardCharsets.UTF_8)));
                                    pendings.remove(path);
                                }
                            }, exec);
                }, exec)
                .exceptionally(e -> null);
    }
}


