package yangqi.async;

import org.apache.zookeeper.KeeperException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;


final class CloseableScheduler implements AutoCloseable {
    final ScheduledExecutorService sch;
    CloseableScheduler(ScheduledExecutorService sch) { this.sch = sch; }
    @Override public void close() { sch.shutdownNow(); }
}

public class DemoMain
{
    public static void main(String[] args) throws Exception {
        String connect = "localhost:2181";
        String id = "1" + ThreadLocalRandom.current().nextInt(1000);

        try (
            CloseableScheduler cs = new CloseableScheduler(Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "demo-scheduler");
                t.setDaemon(true);
                return t;
            }))
        ) {
            ScheduledExecutorService scheduler = cs.sch;
            ZkFutures zf = new ZkFutures(connect, 10_000, event -> {}, scheduler);
            LeaderElector elector = new LeaderElector(zf, id);
            CompletableFuture<Void> started = ZkFutures.withTimeout(
                    ZkFutures.retryAsync(elector::start, 5, Duration.ofMillis(200), scheduler,
                            KeeperException.ConnectionLossException.class,
                            KeeperException.OperationTimeoutException.class),
                    Duration.ofSeconds(30), scheduler);

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
