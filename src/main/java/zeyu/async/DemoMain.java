package zeyu.async;

import org.apache.zookeeper.KeeperException;

import zeyu.async.common.LeaderElector;
import zeyu.async.common.ZkFutures;
import zeyu.async.common.ZkFuturesDecorator;
import zeyu.async.common.ZkFuturesPolicies;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

final class CloseableScheduler implements AutoCloseable {
    final ScheduledExecutorService sch;

    CloseableScheduler(ScheduledExecutorService sch) {
        this.sch = sch;
    }

    @Override
    public void close() {
        sch.shutdownNow();
    }
}

public class DemoMain {
    public static void main(String[] args) throws Exception {
        String connect = "localhost:2181";
        String id = "1" + ThreadLocalRandom.current().nextInt(1000);

        try (
                CloseableScheduler cs = new CloseableScheduler(Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "demo-scheduler");
                    t.setDaemon(true);
                    return t;
                }))) {
            ScheduledExecutorService scheduler = cs.sch;
            try (ZkFutures zfBase = new ZkFutures(connect, 10_000, event -> {})) {
                // Create retry and timeout policies
                ZkFuturesPolicies.RetryPolicy retryPolicy = new ZkFuturesPolicies.RetryPolicy(5, Duration.ofMillis(200),
                        KeeperException.ConnectionLossException.class, KeeperException.OperationTimeoutException.class);
                ZkFuturesPolicies.TimeoutPolicy timeoutPolicy = new ZkFuturesPolicies.TimeoutPolicy(Duration.ofSeconds(30), scheduler);
                ZkFuturesPolicies policies = ZkFuturesPolicies.builder()
                        .retry(retryPolicy, scheduler)
                        .timeout(timeoutPolicy)
                        .build();
                
                try (ZkFuturesDecorator zf = new ZkFuturesDecorator(zfBase, policies);
                     LeaderElector elector = new LeaderElector(zfBase, id,
                        p -> System.out.println("Leader elected:" + p))) {
                    CompletableFuture<Void> started = timeoutPolicy.withTimeout(
                            retryPolicy.retryAsync(elector::start, scheduler));

            started.whenComplete((ok, err) -> {
                if (err != null) {
                    err.printStackTrace();
                } else {
                    System.out.println("Election started");
                }
            });

                    Thread.currentThread().join();
                }
            }
        }

    }
}
