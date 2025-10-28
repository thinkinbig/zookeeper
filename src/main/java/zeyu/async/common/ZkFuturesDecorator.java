package zeyu.async.common;

import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.OpResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Decorator that wraps a ZkFutures and applies optional CircuitBreaker and RetryPolicy
 * from ZkFuturesPolicies.
 */
public final class ZkFuturesDecorator implements AutoCloseable {
    private final ZkFutures delegate;
    private final ZkFuturesPolicies policies;

    public ZkFuturesDecorator(ZkFutures delegate, ZkFuturesPolicies policies) {
        this.delegate = delegate;
        this.policies = policies;
    }

    private <T> CompletableFuture<T> apply(Supplier<CompletableFuture<T>> op) {
        Supplier<CompletableFuture<T>> withCb = op;
        if (policies != null && policies.circuitBreaker != null) {
            withCb = () -> policies.circuitBreaker.execute(op);
        }
        
        CompletableFuture<T> result;
        if (policies != null && policies.retryPolicy != null) {
            result = policies.retryPolicy.retryAsync(withCb, policies.scheduler);
        } else {
            result = withCb.get();
        }
        
        if (policies != null && policies.timeoutPolicy != null) {
            result = policies.timeoutPolicy.withTimeout(result);
        }
        
        return result;
    }

    public CompletableFuture<String> createEphemeral(String path, byte[] data) {
        return apply(() -> delegate.createEphemeral(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE));
    }

    public CompletableFuture<String> createEphemeral(String path, byte[] data, List<org.apache.zookeeper.data.ACL> acl) {
        return apply(() -> delegate.createEphemeral(path, data, acl));
    }

    public CompletableFuture<String> createPersistent(String path, byte[] data) {
        return apply(() -> delegate.createPersistent(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE));
    }

    public CompletableFuture<Void> ensurePersistent(String path) {
        return apply(() -> delegate.ensurePersistent(path));
    }

    public CompletableFuture<ZkFutures.ChildrenSnapshot> getChildren(String path, Watcher watcher) {
        return apply(() -> delegate.getChildren(path, watcher));
    }

    public CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> getChildrenOrEmpty(String path, Watcher watcher) {
        return apply(() -> delegate.getChildrenOrEmpty(path, watcher));
    }

    public CompletableFuture<ZkFutures.NodeData> getData(String path, Watcher watcher) {
        return apply(() -> delegate.getData(path, watcher));
    }

    public CompletableFuture<Optional<ZkFutures.NodeData>> getDataOrEmpty(String path, Watcher watcher) {
        return apply(() -> delegate.getDataOrEmpty(path, watcher));
    }

    public CompletableFuture<Optional<org.apache.zookeeper.data.Stat>> exists(String path, Watcher watcher) {
        return apply(() -> delegate.exists(path, watcher));
    }

    public CompletableFuture<Void> delete(String path, int version) {
        return apply(() -> delegate.delete(path, version));
    }

    public CompletableFuture<org.apache.zookeeper.data.Stat> setData(String path, byte[] data, int version) {
        return apply(() -> delegate.setData(path, data, version));
    }

    public CompletableFuture<List<OpResult>> multi(List<org.apache.zookeeper.Op> ops) {
        return apply(() -> delegate.multi(ops));
    }

    public ZkFutures unwrap() { return delegate; }

    @Override
    public void close() throws Exception { delegate.close(); }
}


