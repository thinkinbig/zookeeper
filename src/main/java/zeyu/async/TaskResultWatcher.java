package zeyu.async;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.OperationTimeoutException;

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
 *
 * 用法：
 * - 任务提交后，调用 await("/status/{taskId}", onUpdate) 等待结果。
 * - 该 watcher 对结果节点挂一次性 watch；被触发或存在即返回数据。
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

    private final String statusRoot = "/status";

    private final ConcurrentHashMap<String, Pending> pendings = new ConcurrentHashMap<>();

    public TaskResultWatcher(ZkFutures zf) {
        this.zf = zf;
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
        if (statusRoot == null || statusRoot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return zf.ensurePersistent(statusRoot)
                .thenComposeAsync(v -> {
                    // 重新挂表全部待观察的路径
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
            // 等待新的 SyncConnected 再继续
            return;
        }
        if (event.getType() == Event.EventType.None && event.getState() == Event.KeeperState.SyncConnected) {
            // 重新挂表全部待观察的路径
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

        // 先 exists 以便在节点不存在时挂上 NodeCreated 的一次性 watch
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
                        // 不存在：已挂表等待 NodeCreated，先返回
                        return CompletableFuture.completedFuture(null);
                    }
                    // 已存在：读取数据并在回调线程里完成
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


