package zeyu.async.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import zeyu.async.client.TaskResultWatcher;
import zeyu.async.common.CircuitBreaker;
import zeyu.async.common.ZkFutures;
import zeyu.async.common.ZkFuturesDecorator;
import zeyu.async.common.ZkFuturesPolicies;
import zeyu.async.server.TasksAssigner;
import zeyu.async.server.WorkerAgent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.zookeeper.KeeperException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class EndToEndIT {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> zk = new GenericContainer<>("zookeeper:3.9")
            .withExposedPorts(2181)
            .withStartupTimeout(Duration.ofSeconds(30));

    private TasksAssigner assigner;
    private WorkerAgent agent;
    private TaskResultWatcher watcher;
    private ScheduledExecutorService scheduler;

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) watcher.close();
        if (assigner != null) assigner.close();
        if (agent != null) agent.close();
        if (scheduler != null) scheduler.shutdown();
    }

    @Test
    @Timeout(40)
    void endToEnd_multiEnabled() throws Exception {
        runScenario(true);
    }

    @Test
    @Timeout(40)
    void endToEnd_circuitBreakerTest() throws Exception {
        runCircuitBreakerScenario();
    }

    @Test
    @Timeout(40)
    void endToEnd_retryTest() throws Exception {
        runRetryScenario();
    }

    private void runScenario(boolean multiEnabled) throws Exception {
        String connect = zk.getHost() + ":" + zk.getMappedPort(2181);
        
        // Create shared scheduler for retry policies
        scheduler = Executors.newScheduledThreadPool(4);
        
        // Create base ZkFutures instances
        ZkFutures zfClientBase = new ZkFutures(connect, 8_000, e -> {}, multiEnabled);
        ZkFutures zfAssignerBase = new ZkFutures(connect, 8_000, e -> {}, multiEnabled);
        ZkFutures zfWorkerBase = new ZkFutures(connect, 8_000, e -> {}, multiEnabled);
        
        // Create policies with circuit breaker and retry
        CircuitBreaker cb = new CircuitBreaker();
        cb.setFailureThreshold(3);
        cb.setOpenSleepWindowMs(5000);
        cb.setHalfOpenMaxInFlight(2);
        
        ZkFuturesPolicies.RetryPolicy retryPolicy = new ZkFuturesPolicies.RetryPolicy(3, Duration.ofMillis(200), 
                KeeperException.ConnectionLossException.class, KeeperException.OperationTimeoutException.class);
        
        ZkFuturesPolicies policies = ZkFuturesPolicies.builder()
                .circuitBreaker(cb)
                .retry(retryPolicy, scheduler)
                .build();
        
        // Wrap with decorators
        ZkFuturesDecorator zfClient = new ZkFuturesDecorator(zfClientBase, policies);
        ZkFuturesDecorator zfAssigner = new ZkFuturesDecorator(zfAssignerBase, policies);
        ZkFuturesDecorator zfWorker = new ZkFuturesDecorator(zfWorkerBase, policies);
        
        try {
            String tasks = "/tasks";
            String claims = "/claims";
            String assign = "/assign";
            String workers = "/workers";
            String status = "/status";
            String workerId = "w1";
            String taskId = "e2e-1";

            CompletableFuture.allOf(
                    zfClient.ensurePersistent(tasks),
                    zfClient.ensurePersistent(claims),
                    zfClient.ensurePersistent(assign),
                    zfClient.ensurePersistent(workers),
                    zfClient.ensurePersistent(status),
                    zfClient.ensurePersistent(assign + "/" + workerId)
            ).get(5, java.util.concurrent.TimeUnit.SECONDS);

            // Start WorkerAgent that writes status OK for the task
            agent = new WorkerAgent(zfWorkerBase, workerId, workers, assign, status, nd -> {
                // echo processing; body not needed, WorkerAgent writes status in its flow
            });
            agent.start().get(5, java.util.concurrent.TimeUnit.SECONDS);

            // Ensure worker is registered before creating tasks
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertTrue(zfClient.exists(workers + "/" + workerId, null).get().isPresent());
            });

            // Start TaskResultWatcher to wait for result
            watcher = new TaskResultWatcher(zfClientBase, status);
            watcher.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            var resultFuture = watcher.await(status + "/" + taskId, null);

            // If in compensation mode, inject an orphan claim BEFORE starting assigner, so startup-compensation can clean it
            if (!multiEnabled) {
                String orphan = "orphan-claim";
                zfClient.createPersistent(claims + "/" + orphan, new byte[0]).get();
            }

            // Start TasksAssigner to assign task to worker
            assigner = new TasksAssigner(zfAssignerBase, tasks, claims, assign, nd -> workerId);
            assigner.start().get(5, java.util.concurrent.TimeUnit.SECONDS);

            // Verify compensation cleaned orphan claim (if any)
            if (!multiEnabled) {
                String orphan = "orphan-claim";
                // explicitly trigger one compensation cycle to make cleanup deterministic
                assigner.runCompensation();
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                        assertTrue(zfClient.exists(claims + "/" + orphan, null).get().isEmpty())
                );
            }

            // Create task
            zfClient.createPersistent(tasks + "/" + taskId, "payload".getBytes(StandardCharsets.UTF_8)).get();

            // Await final result written by WorkerAgent after it processes assigned task
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertTrue(resultFuture.isDone());
                Optional<String> val = resultFuture.getNow(Optional.empty());
                assertTrue(val.isPresent());
                assertEquals("OK", val.get());

                // Verify cleanup: task, claim and assignment should be removed
                assertTrue(zfClient.exists(tasks + "/" + taskId, null).get().isEmpty());
                assertTrue(zfClient.exists(claims + "/" + taskId, null).get().isEmpty());
                assertTrue(zfClient.exists(assign + "/" + workerId + "/" + taskId, null).get().isEmpty());
            });
        } finally {
            try { zfClient.close(); } catch (Exception ignored) {}
            try { zfAssigner.close(); } catch (Exception ignored) {}
            try { zfWorker.close(); } catch (Exception ignored) {}
            try { zfClientBase.close(); } catch (Exception ignored) {}
            try { zfAssignerBase.close(); } catch (Exception ignored) {}
            try { zfWorkerBase.close(); } catch (Exception ignored) {}
        }
    }

    private void runCircuitBreakerScenario() throws Exception {
        String connect = zk.getHost() + ":" + zk.getMappedPort(2181);
        
        // Create scheduler
        scheduler = Executors.newScheduledThreadPool(2);
        
        // Create aggressive circuit breaker (fails fast)
        CircuitBreaker cb = new CircuitBreaker();
        cb.setFailureThreshold(2);  // Trip after 2 failures
        cb.setOpenSleepWindowMs(1000); // Short sleep window
        cb.setHalfOpenMaxInFlight(1);  // Only 1 probe
        
        ZkFuturesPolicies policies = ZkFuturesPolicies.builder()
                .circuitBreaker(cb)
                .build();
        
        ZkFutures zfBase = new ZkFutures(connect, 8_000, e -> {}, true);
        ZkFuturesDecorator zf = new ZkFuturesDecorator(zfBase, policies);
        
        try {
            // First, create a valid path
            zf.ensurePersistent("/test").get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            // Now try to access a non-existent path multiple times to trigger circuit breaker
            for (int i = 0; i < 3; i++) {
                try {
                    zf.getData("/non-existent-path", null).get(1, java.util.concurrent.TimeUnit.SECONDS);
                    fail("Should have failed");
                } catch (Exception e) {
                    System.out.println("Attempt " + (i + 1) + " failed as expected: " + e.getMessage());
                }
            }
            
            // Circuit breaker should be OPEN now
            assertEquals(CircuitBreaker.State.OPEN, cb.getState());
            System.out.println("Circuit breaker is OPEN as expected");
            
            // Schedule the circuit breaker to probe after sleep window
            cb.scheduleCloseProbe(scheduler);
            
            // Try one more operation - should fail immediately with circuit breaker exception
            try {
                zf.getData("/another-path", null).get(1, java.util.concurrent.TimeUnit.SECONDS);
                fail("Should have been blocked by circuit breaker");
            } catch (Exception e) {
                System.out.println("Blocked by circuit breaker: " + e.getMessage());
                assertTrue(e.getMessage().contains("circuit-open"));
            }
            
            // Wait for circuit breaker to go to HALF_OPEN using Awaitility
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                System.out.println("Checking circuit breaker state: " + cb.getState());
                System.out.println("Opened at: " + cb.getOpenedAtMs() + ", now: " + System.currentTimeMillis());
                System.out.println("Time elapsed: " + (System.currentTimeMillis() - cb.getOpenedAtMs()) + "ms");
                assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
            });
            System.out.println("Circuit breaker is HALF_OPEN as expected");
            
        } finally {
            try { zf.close(); } catch (Exception ignored) {}
            try { zfBase.close(); } catch (Exception ignored) {}
        }
    }

    private void runRetryScenario() throws Exception {
        String connect = zk.getHost() + ":" + zk.getMappedPort(2181);
        
        // Create scheduler
        scheduler = Executors.newScheduledThreadPool(2);
        
        // Create retry policy
        ZkFuturesPolicies.RetryPolicy retryPolicy = new ZkFuturesPolicies.RetryPolicy(3, Duration.ofMillis(100), 
                KeeperException.ConnectionLossException.class, KeeperException.OperationTimeoutException.class);
        
        ZkFuturesPolicies policies = ZkFuturesPolicies.builder()
                .retry(retryPolicy, scheduler)
                .build();
        
        ZkFutures zfBase = new ZkFutures(connect, 8_000, e -> {}, true);
        ZkFuturesDecorator zf = new ZkFuturesDecorator(zfBase, policies);
        
        try {
            // Test successful operation (should work on first try)
            long startTime = System.currentTimeMillis();
            zf.ensurePersistent("/retry-test").get(5, java.util.concurrent.TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("Successful operation took: " + duration + "ms");
            
            // Test operation that will fail (non-existent path)
            // This should fail immediately without retries since it's not a retryable exception
            startTime = System.currentTimeMillis();
            try {
                zf.getData("/non-existent-path", null).get(1, java.util.concurrent.TimeUnit.SECONDS);
                fail("Should have failed");
            } catch (Exception e) {
                duration = System.currentTimeMillis() - startTime;
                System.out.println("Non-retryable failure took: " + duration + "ms");
                assertTrue(e.getMessage().contains("NoNode"));
            }
            
            // Test with a path that exists (should succeed)
            zf.setData("/retry-test", "test-data".getBytes(), -1).get(5, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("Data update succeeded");
            
        } finally {
            try { zf.close(); } catch (Exception ignored) {}
            try { zfBase.close(); } catch (Exception ignored) {}
        }
    }
}


