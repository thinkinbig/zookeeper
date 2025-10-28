package zeyu.async.common;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ZkFutures implements AutoCloseable {
    private final ZooKeeper zk;
    private final boolean multiEnabled;

    public ZkFutures(String connectString, int sessionTimeoutMs,
            Watcher defaultWatcher) throws IOException {
        this(connectString, sessionTimeoutMs, defaultWatcher, true);
    }

    public ZkFutures(String connectString, int sessionTimeoutMs,
            Watcher defaultWatcher, boolean multiEnabled) throws IOException {
        this.zk = new ZooKeeper(connectString, sessionTimeoutMs, defaultWatcher);
        this.multiEnabled = multiEnabled;
    }

    public ZooKeeper raw() {
        return zk;
    }

    public boolean isMultiEnabled() {
        return multiEnabled;
    }

    public CompletableFuture<String> createEphemeral(String path, byte[] data,
            List<ACL> acl) {
        CompletableFuture<String> cf = new CompletableFuture<>();
        zk.create(path, data, acl, CreateMode.EPHEMERAL, (rc, p, ctx, name) -> completeByCode(cf, rc, name, p), null);
        return cf;
    }

    public CompletableFuture<String> createEphemeralSequential(String path, byte[] data, List<ACL> acl) {
        CompletableFuture<String> cf = new CompletableFuture<>();
        zk.create(path, data, acl, CreateMode.EPHEMERAL_SEQUENTIAL,
                (rc, p, ctx, name) -> completeByCode(cf, rc, name, p), null);
        return cf;
    }

    public CompletableFuture<String> createPersistent(String path, byte[] data, List<ACL> acl) {
        CompletableFuture<String> cf = new CompletableFuture<>();
        zk.create(path, data, acl, CreateMode.PERSISTENT, (rc, p, ctx, name) -> completeByCode(cf, rc, name, p), null);
        return cf;
    }

    public CompletableFuture<Void> ensurePersistent(String path) {
        return createPersistent(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE)
                .thenApply(p -> (Void) null)
                .exceptionally(ex -> {
                    Throwable t = ZkFutures.unwrap(ex);
                    if (t instanceof KeeperException.NodeExistsException)
                        return null; // 当成功
                    throw new CompletionException(t);
                });
    }

    public CompletableFuture<String> createPersistentSequential(String path, byte[] data, List<ACL> acl) {
        CompletableFuture<String> cf = new CompletableFuture<>();
        zk.create(path, data, acl, CreateMode.PERSISTENT_SEQUENTIAL,
                (rc, p, ctx, name) -> completeByCode(cf, rc, name, p), null);
        return cf;
    }

    public record ChildrenSnapshot(List<String> children, Stat stat) {
    }

    public CompletableFuture<ChildrenSnapshot> getChildren(String path, Watcher watcher) {
        CompletableFuture<ChildrenSnapshot> cf = new CompletableFuture<>();
        zk.getChildren(path, watcher,
                (rc, p, ctx, children, stat) -> completeByCode(cf, rc,
                        new ChildrenSnapshot(Collections.unmodifiableList(children), stat), p),
                null);
        return cf;
    }

    public CompletableFuture<Optional<ChildrenSnapshot>> getChildrenOrEmpty(String path, Watcher watcher) {
        CompletableFuture<Optional<ChildrenSnapshot>> cf = new CompletableFuture<>();
        zk.getChildren(path, watcher,
                (rc, p, ctx, children, stat) -> completeByCodeOrEmpty(cf, rc,
                        new ChildrenSnapshot(Collections.unmodifiableList(children), stat), p),
                null);
        return cf;
    }

    public record NodeData(byte[] data, Stat stat) {
    }

    public CompletableFuture<NodeData> getData(String path, Watcher watcher) {
        CompletableFuture<NodeData> cf = new CompletableFuture<>();
        zk.getData(path, watcher, (rc, p, ctx, data, stat) -> completeByCode(cf, rc,
                new NodeData(data, stat), p), null);
        return cf;
    }

    public CompletableFuture<Optional<NodeData>> getDataOrEmpty(String path, Watcher watcher) {
        CompletableFuture<Optional<NodeData>> cf = new CompletableFuture<>();
        zk.getData(path, watcher, (rc, p, ctx, data, stat) -> completeByCodeOrEmpty(cf, rc,
                new NodeData(data, stat), p), null);
        return cf;
    }

    public CompletableFuture<Optional<Stat>> exists(String path, Watcher watcher) {
        var cf = new CompletableFuture<Optional<Stat>>();
        zk.exists(path, watcher,
                (rc, p, ctx, stat) -> completeByCodeOrEmpty(cf, rc, stat, p), null);
        return cf;
    }

    public CompletableFuture<Void> delete(String path, int version) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        zk.delete(path, version, (rc, p, ctx) -> completeByCode(cf, rc, (Void) null, p), null);
        return cf;
    }

    public CompletableFuture<Stat> setData(String path, byte[] data, int version) {
        CompletableFuture<Stat> cf = new CompletableFuture<>();
        zk.setData(path, data, version, (rc, p, ctx, stat) -> completeByCode(cf, rc, stat, p), null);
        return cf;
    }

    public CompletableFuture<List<OpResult>> multi(List<Op> ops) {
        CompletableFuture<List<OpResult>> cf = new CompletableFuture<>();
        zk.multi(ops, (rc, path, ctx, results) -> {
            var code = KeeperException.Code.get(rc);
            if (code == KeeperException.Code.OK) {
                cf.complete(results);
            } else {
                cf.completeExceptionally(KeeperException.create(code, path));
            }
        }, null);
        return cf;
    }

    private static <T> void completeByCode(CompletableFuture<T> cf, int rc, T okVal, String path) {
        var code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK)
            cf.complete(okVal);
        else
            cf.completeExceptionally(KeeperException.create(code, path));
    }

    private static <T> void completeByCodeOrEmpty(CompletableFuture<Optional<T>> cf, int rc, T okVal, String path) {
        var code = KeeperException.Code.get(rc);
        if (code == KeeperException.Code.OK)
            cf.complete(Optional.ofNullable(okVal));
        else if (code == KeeperException.Code.NONODE)
            cf.complete(Optional.empty());
        else
            cf.completeExceptionally(KeeperException.create(code, path));
    }

    // timeout helpers moved to ZkFuturesPolicies.TimeoutPolicy

    public static Throwable unwrap(Throwable e) {
        if (e instanceof CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }

        return e;
    }

    /**
     * 流式 MultiOps 构建器
     */
    public static class MultiOps {
        private final List<Op> operations = new ArrayList<>();

        private MultiOps() {
        }

        public static MultiOps create() {
            return new MultiOps();
        }

        public MultiOps create(String path, byte[] data, List<ACL> acl, CreateMode createMode) {
            operations.add(Op.create(path, data, acl, createMode));
            return this;
        }

        public MultiOps create(String path, byte[] data, List<ACL> acl) {
            return create(path, data, acl, CreateMode.PERSISTENT);
        }

        public MultiOps createEphemeral(String path, byte[] data, List<ACL> acl) {
            return create(path, data, acl, CreateMode.EPHEMERAL);
        }

        public MultiOps createEphemeralSequential(String path, byte[] data, List<ACL> acl) {
            return create(path, data, acl, CreateMode.EPHEMERAL_SEQUENTIAL);
        }

        public MultiOps delete(String path, int version) {
            operations.add(Op.delete(path, version));
            return this;
        }

        public MultiOps setData(String path, byte[] data, int version) {
            operations.add(Op.setData(path, data, version));
            return this;
        }

        public MultiOps check(String path, int version) {
            operations.add(Op.check(path, version));
            return this;
        }

        public List<Op> build() {
            return new ArrayList<>(operations);
        }

        public CompletableFuture<List<OpResult>> execute(ZkFutures zkFutures) {
            return zkFutures.multi(build());
        }
    }

    @Override
    public void close() throws Exception {
        zk.close();
    }
}
