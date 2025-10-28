package zeyu.async.integration;

import org.apache.zookeeper.ZooDefs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zeyu.async.client.TaskResultWatcher;
import zeyu.async.common.ZkFutures;
import zeyu.async.server.TasksAssigner;
import zeyu.async.server.WorkerAgent;
import zeyu.async.server.WorkersWatcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ConcurrencyIT {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> zk = new GenericContainer<>("zookeeper:3.9")
            .withExposedPorts(2181)
            .withStartupTimeout(Duration.ofSeconds(30));

    private ZkFutures zfClient;
    private ZkFutures zfAssigner;
    private ZkFutures zfWorker1;
    private ZkFutures zfWorker2;
    private ZkFutures zfWorker3;
    private TasksAssigner tasksAssigner;
    private WorkersWatcher workersWatcher;
    private WorkerAgent workerAgent1;
    private WorkerAgent workerAgent2;
    private WorkerAgent workerAgent3;
    private TaskResultWatcher taskResultWatcher;

    private static final String WORKER_ID_1 = "w1";
    private static final String WORKER_ID_2 = "w2";
    private static final String WORKER_ID_3 = "w3";
    private static final String TASKS_PATH = "/tasks";
    private static final String CLAIMS_PATH = "/claims";
    private static final String ASSIGN_PATH = "/assign";
    private static final String WORKERS_PATH = "/workers";
    private static final String STATUS_PATH = "/status";

    @BeforeEach
    void setUp() throws Exception {
        String connectString = "localhost:" + zk.getMappedPort(2181);
        zfClient = new ZkFutures(connectString, 8000, event -> {}, true);
        zfAssigner = new ZkFutures(connectString, 8000, event -> {}, true);
        zfWorker1 = new ZkFutures(connectString, 8000, event -> {}, true);
        zfWorker2 = new ZkFutures(connectString, 8000, event -> {}, true);
        zfWorker3 = new ZkFutures(connectString, 8000, event -> {}, true);

        // Initialize WorkersWatcher
        AtomicReference<WorkersWatcher.Snapshot> lastSnapshot = new AtomicReference<>();
        AtomicReference<WorkersWatcher.Diff> lastDiff = new AtomicReference<>();
        CountDownLatch workerChangeLatch = new CountDownLatch(3);
        workersWatcher = new WorkersWatcher(WORKERS_PATH, zfClient, (snapshot, diff) -> {
            lastSnapshot.set(snapshot);
            lastDiff.set(diff);
            workerChangeLatch.countDown();
        });

        // Initialize TasksAssigner with round-robin worker selection
        AtomicInteger workerIndex = new AtomicInteger(0);
        List<String> workers = List.of(WORKER_ID_1, WORKER_ID_2, WORKER_ID_3);
        Function<ZkFutures.NodeData, String> pickWorker = data -> {
            int index = workerIndex.getAndIncrement() % workers.size();
            return workers.get(index);
        };
        tasksAssigner = new TasksAssigner(zfAssigner, TASKS_PATH, CLAIMS_PATH, ASSIGN_PATH, pickWorker);

        // Initialize WorkerAgents
        AtomicInteger processedCount1 = new AtomicInteger(0);
        AtomicInteger processedCount2 = new AtomicInteger(0);
        AtomicInteger processedCount3 = new AtomicInteger(0);
        
        workerAgent1 = new WorkerAgent(zfWorker1, WORKER_ID_1, WORKERS_PATH, ASSIGN_PATH, STATUS_PATH, data -> {
            processedCount1.incrementAndGet();
        });
        
        workerAgent2 = new WorkerAgent(zfWorker2, WORKER_ID_2, WORKERS_PATH, ASSIGN_PATH, STATUS_PATH, data -> {
            processedCount2.incrementAndGet();
        });
        
        workerAgent3 = new WorkerAgent(zfWorker3, WORKER_ID_3, WORKERS_PATH, ASSIGN_PATH, STATUS_PATH, data -> {
            processedCount3.incrementAndGet();
        });

        // Initialize TaskResultWatcher
        taskResultWatcher = new TaskResultWatcher(zfClient, STATUS_PATH);

        // Start all components
        workersWatcher.start().get(5, TimeUnit.SECONDS);
        tasksAssigner.start().get(5, TimeUnit.SECONDS);
        workerAgent1.start().get(5, TimeUnit.SECONDS);
        workerAgent2.start().get(5, TimeUnit.SECONDS);
        workerAgent3.start().get(5, TimeUnit.SECONDS);
        taskResultWatcher.start().get(5, TimeUnit.SECONDS);

        // Await all workers registration
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertTrue(lastSnapshot.get().children().contains(WORKER_ID_1));
            assertTrue(lastSnapshot.get().children().contains(WORKER_ID_2));
            assertTrue(lastSnapshot.get().children().contains(WORKER_ID_3));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (taskResultWatcher != null) taskResultWatcher.close();
        if (workerAgent3 != null) workerAgent3.close();
        if (workerAgent2 != null) workerAgent2.close();
        if (workerAgent1 != null) workerAgent1.close();
        if (tasksAssigner != null) tasksAssigner.close();
        if (workersWatcher != null) workersWatcher.close();
        if (zfClient != null) zfClient.close();
        if (zfAssigner != null) zfAssigner.close();
        if (zfWorker1 != null) zfWorker1.close();
        if (zfWorker2 != null) zfWorker2.close();
        if (zfWorker3 != null) zfWorker3.close();
    }

    @Test
    void testConcurrentTaskSubmission() throws Exception {
        int taskCount = 20; // Reduced for faster test
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Submit tasks concurrently
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String taskPath = TASKS_PATH + "/concurrent-" + taskId;
                    String statusPath = STATUS_PATH + "/concurrent-" + taskId;
                    String taskPayload = "concurrent-task-" + taskId;

                    // Start watching for result before submitting task
                    CompletableFuture<Optional<String>> resultFuture = taskResultWatcher.await(statusPath, null);
                    
                    // Submit task
                    zfClient.createPersistent(taskPath, taskPayload.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE).get();

                    // Wait for result
                    String result = resultFuture.get(15, TimeUnit.SECONDS).orElseThrow();
                    successCount.incrementAndGet();
                    return result;
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    throw new RuntimeException("Task " + taskId + " failed", e);
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all tasks to complete
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allTasks.get(30, TimeUnit.SECONDS);

        // Verify results
        assertEquals(taskCount, successCount.get(), "All tasks should succeed");
        assertEquals(0, errorCount.get(), "No tasks should fail");
        
        // Verify all tasks were processed
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            // Check that all tasks are cleaned up
            List<String> remainingTasks = zfClient.getChildrenOrEmpty(TASKS_PATH, null).get().orElse(new ZkFutures.ChildrenSnapshot(Collections.emptyList(), null)).children();
            assertTrue(remainingTasks.isEmpty(), "All tasks should be processed and removed");
            
            List<String> remainingClaims = zfClient.getChildrenOrEmpty(CLAIMS_PATH, null).get().orElse(new ZkFutures.ChildrenSnapshot(Collections.emptyList(), null)).children();
            assertTrue(remainingClaims.isEmpty(), "All claims should be cleaned up");
            
            List<String> remainingAssignments = new ArrayList<>();
            remainingAssignments.addAll(zfClient.getChildrenOrEmpty(ASSIGN_PATH + "/" + WORKER_ID_1, null).get().orElse(new ZkFutures.ChildrenSnapshot(Collections.emptyList(), null)).children());
            remainingAssignments.addAll(zfClient.getChildrenOrEmpty(ASSIGN_PATH + "/" + WORKER_ID_2, null).get().orElse(new ZkFutures.ChildrenSnapshot(Collections.emptyList(), null)).children());
            remainingAssignments.addAll(zfClient.getChildrenOrEmpty(ASSIGN_PATH + "/" + WORKER_ID_3, null).get().orElse(new ZkFutures.ChildrenSnapshot(Collections.emptyList(), null)).children());
            assertTrue(remainingAssignments.isEmpty(), "All assignments should be cleaned up");
        });

        executor.shutdown();
    }

    @Test
    void testWorkerFailureAndRecovery() throws Exception {
        int taskCount = 15; // Reduced for faster test
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        // Submit first batch of tasks
        for (int i = 0; i < taskCount / 2; i++) {
            final int taskId = i;
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String taskPath = TASKS_PATH + "/failure-" + taskId;
                    String statusPath = STATUS_PATH + "/failure-" + taskId;
                    String taskPayload = "failure-task-" + taskId;

                    CompletableFuture<Optional<String>> resultFuture = taskResultWatcher.await(statusPath, null);
                    zfClient.createPersistent(taskPath, taskPayload.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE).get();
                    String result = resultFuture.get(15, TimeUnit.SECONDS).orElseThrow();
                    successCount.incrementAndGet();
                    return result;
                } catch (Exception e) {
                    throw new RuntimeException("Task " + taskId + " failed", e);
                }
            }, executor);
            futures.add(future);
        }

        // Simulate worker failure by closing one worker
        Thread.sleep(1000); // Let some tasks be assigned
        workerAgent2.close();
        zfWorker2.close();

        // Submit second batch of tasks
        for (int i = taskCount / 2; i < taskCount; i++) {
            final int taskId = i;
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String taskPath = TASKS_PATH + "/failure-" + taskId;
                    String statusPath = STATUS_PATH + "/failure-" + taskId;
                    String taskPayload = "failure-task-" + taskId;

                    CompletableFuture<Optional<String>> resultFuture = taskResultWatcher.await(statusPath, null);
                    zfClient.createPersistent(taskPath, taskPayload.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE).get();
                    String result = resultFuture.get(15, TimeUnit.SECONDS).orElseThrow();
                    successCount.incrementAndGet();
                    return result;
                } catch (Exception e) {
                    throw new RuntimeException("Task " + taskId + " failed", e);
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all tasks to complete
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allTasks.get(30, TimeUnit.SECONDS);

        // Verify that tasks were redistributed to remaining workers
        assertEquals(taskCount, successCount.get(), "All tasks should succeed despite worker failure");
        
        // Verify cleanup
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> remainingTasks = zfClient.getChildrenOrEmpty(TASKS_PATH, null).get().orElse(new ZkFutures.ChildrenSnapshot(Collections.emptyList(), null)).children();
            assertTrue(remainingTasks.isEmpty(), "All tasks should be processed");
        });

        executor.shutdown();
    }

    @Test
    void testLoadBalancing() throws Exception {
        int taskCount = 15; // Reduced for faster test
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        AtomicInteger worker1Count = new AtomicInteger(0);
        AtomicInteger worker2Count = new AtomicInteger(0);
        AtomicInteger worker3Count = new AtomicInteger(0);

        // Submit tasks sequentially to better track assignments
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String taskPath = TASKS_PATH + "/load-" + taskId;
                    String statusPath = STATUS_PATH + "/load-" + taskId;
                    String taskPayload = "load-task-" + taskId;

                    CompletableFuture<Optional<String>> resultFuture = taskResultWatcher.await(statusPath, null);
                    zfClient.createPersistent(taskPath, taskPayload.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE).get();
                    
                    // Wait for assignment and track which worker got it
                    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                        String assignPath1 = ASSIGN_PATH + "/" + WORKER_ID_1 + "/load-" + taskId;
                        String assignPath2 = ASSIGN_PATH + "/" + WORKER_ID_2 + "/load-" + taskId;
                        String assignPath3 = ASSIGN_PATH + "/" + WORKER_ID_3 + "/load-" + taskId;
                        
                        if (zfClient.exists(assignPath1, null).get().isPresent()) {
                            worker1Count.incrementAndGet();
                        } else if (zfClient.exists(assignPath2, null).get().isPresent()) {
                            worker2Count.incrementAndGet();
                        } else if (zfClient.exists(assignPath3, null).get().isPresent()) {
                            worker3Count.incrementAndGet();
                        }
                    });
                    
                    String result = resultFuture.get(15, TimeUnit.SECONDS).orElseThrow();
                    return result;
                } catch (Exception e) {
                    throw new RuntimeException("Task " + taskId + " failed", e);
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all tasks to complete
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allTasks.get(30, TimeUnit.SECONDS);

        // Verify load balancing (tasks should be distributed relatively evenly)
        int totalProcessed = worker1Count.get() + worker2Count.get() + worker3Count.get();
        assertEquals(taskCount, totalProcessed, "All tasks should be processed");
        
        // Check that load is reasonably balanced (each worker should process at least some tasks)
        assertTrue(worker1Count.get() > 0, "Worker 1 should process some tasks");
        assertTrue(worker2Count.get() > 0, "Worker 2 should process some tasks");
        assertTrue(worker3Count.get() > 0, "Worker 3 should process some tasks");
        
        // Check that load is reasonably balanced (no worker should process more than 2x the average)
        int averageLoad = taskCount / 3;
        assertTrue(worker1Count.get() <= averageLoad * 2, "Worker 1 load should be balanced");
        assertTrue(worker2Count.get() <= averageLoad * 2, "Worker 2 load should be balanced");
        assertTrue(worker3Count.get() <= averageLoad * 2, "Worker 3 load should be balanced");

        executor.shutdown();
    }
}
