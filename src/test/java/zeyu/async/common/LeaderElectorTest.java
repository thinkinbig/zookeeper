package zeyu.async.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class LeaderElectorTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> zookeeper = new GenericContainer<>("zookeeper:3.9")
            .withEnv("ALLOW_ANONYMOUS_LOGIN", "yes")
            .withEnv("ZOO_PORT_NUMBER", "2181")
            .withExposedPorts(2181)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(60));

    private ScheduledExecutorService scheduler;
    private LeaderElector elector1;
    private LeaderElector elector2;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "test-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        String connectString = zookeeper.getHost() + ":" + zookeeper.getMappedPort(2181);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (elector1 != null) elector1.close();
        if (elector2 != null) elector2.close();
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Test
    void testSingleLeaderElection() throws Exception {
        AtomicReference<String> electedLeader = new AtomicReference<>();
        CountDownLatch electedLatch = new CountDownLatch(1);

        String connectString = zookeeper.getHost() + ":" + zookeeper.getMappedPort(2181);
        ZkFutures zf = new ZkFutures(connectString, 10000, event -> {}, scheduler);
        elector1 = new LeaderElector(zf, "server1", leader -> {
                electedLeader.set(leader);
                electedLatch.countDown();
            });


        // 启动选举
        CompletableFuture<Void> startFuture = elector1.start();
        startFuture.get(5, TimeUnit.SECONDS);

        // 等待当选
        await().atMost(10, TimeUnit.SECONDS).until(() -> electedLeader.get() != null);
        assertEquals("server1", electedLeader.get());
        zf.close();
    }

    @Test
    void testMultipleLeaderElection() throws Exception {
        AtomicReference<String> electedLeader1 = new AtomicReference<>();
        AtomicReference<String> electedLeader2 = new AtomicReference<>();
        CountDownLatch electedLatch1 = new CountDownLatch(1);
        CountDownLatch electedLatch2 = new CountDownLatch(1);

        // 独立会话的两个 elector
        String connectString = zookeeper.getHost() + ":" + zookeeper.getMappedPort(2181);
        ZkFutures zf1 = new ZkFutures(connectString, 10000, e -> {}, scheduler);
        ZkFutures zf2 = new ZkFutures(connectString, 10000, e -> {}, scheduler);
        try {
            elector1 = new LeaderElector(zf1, "server1", leader -> {
                electedLeader1.set(leader);
                electedLatch1.countDown();
            });

            elector2 = new LeaderElector(zf2, "server2", leader -> {
                electedLeader2.set(leader);
                electedLatch2.countDown();
            });

            // 同时启动两个选举器
            CompletableFuture<Void> start1 = elector1.start();
            CompletableFuture<Void> start2 = elector2.start();

            CompletableFuture.allOf(start1, start2).get(5, TimeUnit.SECONDS);

            // 只有一个能当选（等待任一一侧产生）
            await().atMost(10, TimeUnit.SECONDS).until(() -> electedLeader1.get() != null || electedLeader2.get() != null);
            boolean elected1 = electedLeader1.get() != null;
            boolean elected2 = electedLeader2.get() != null;
            assertTrue(elected1 ^ elected2, "Exactly one leader should be elected");
            
            if (elected1) {
                assertEquals("server1", electedLeader1.get());
                assertNull(electedLeader2.get());
            } else {
                assertEquals("server2", electedLeader2.get());
                assertNull(electedLeader1.get());
            }
        } finally {
            try { zf1.close(); } catch (Exception ignore) {}
            try { zf2.close(); } catch (Exception ignore) {}
        }
    }

    @Test
    void testLeaderFailover() throws Exception {
        AtomicReference<String> electedLeader = new AtomicReference<>();
        CountDownLatch electedLatch = new CountDownLatch(2);

        // use separate ZooKeeper sessions per elector
        String connectString = zookeeper.getHost() + ":" + zookeeper.getMappedPort(2181);
        ZkFutures zf1 = new ZkFutures(connectString, 10000, e -> {}, scheduler);
        ZkFutures zf2 = new ZkFutures(connectString, 10000, e -> {}, scheduler);

        elector1 = new LeaderElector(zf1, "server1", leader -> {
            electedLeader.set(leader);
            electedLatch.countDown();
        });

        elector2 = new LeaderElector(zf2, "server2", leader -> {
            electedLeader.set(leader);
            electedLatch.countDown();
        });

        // 启动第一个选举器
        elector1.start().get(5, TimeUnit.SECONDS);
        await().atMost(10, TimeUnit.SECONDS).until(() -> "server1".equals(electedLeader.get()));

        // 启动第二个选举器，应该等待
        elector2.start().get(5, TimeUnit.SECONDS);
        
        // 关闭第一个选举器
        zf1.close(); // expire leader session; /master EPHEMERAL will be removed by server
        
        // 等待第二个选举器当选
        await().atMost(10, TimeUnit.SECONDS).until(() -> "server2".equals(electedLeader.get()));

        // cleanup
        zf2.close();
    }

    @Test
    void testSessionExpiry() throws Exception {
        AtomicReference<String> electedLeader = new AtomicReference<>();
        CountDownLatch electedLatch = new CountDownLatch(1);

        String connectString = zookeeper.getHost() + ":" + zookeeper.getMappedPort(2181);
        try (ZkFutures zf1 = new ZkFutures(connectString, 10000, event -> {
        }, scheduler); ZkFutures zf2 = new ZkFutures(connectString, 10000, event -> {
        }, scheduler)) {
            // 不 countDown，等待 server2 当选
            elector1 = new LeaderElector(zf1, "server1", electedLeader::set);

            elector2 = new LeaderElector(zf2, "server2", leader -> {
                electedLeader.set(leader);
                electedLatch.countDown();
            });

            // 启动第一个选举器
            elector1.start().get(5, TimeUnit.SECONDS);
            await().atMost(10, TimeUnit.SECONDS).until(() -> "server1".equals(electedLeader.get()));

            // 启动第二个选举器
            elector2.start().get(5, TimeUnit.SECONDS);

            // 模拟会话过期 - 关闭 ZK 连接
            zf1.close();

            // 等待 server2 当选
            await().atMost(10, TimeUnit.SECONDS).until(() -> "server2".equals(electedLeader.get()));
        }
    }
}
