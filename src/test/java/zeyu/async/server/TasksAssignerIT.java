package zeyu.async.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import zeyu.async.common.ZkFutures;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TasksAssignerIT {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> zkContainer = new GenericContainer<>("zookeeper:3.9")
            .withExposedPorts(2181);

    private TasksAssigner assigner;

    @AfterEach
    void tearDown() {
        if (assigner != null) {
            assigner.close();
        }
    }

    @Test
    @Timeout(20)
    void endToEnd_assignsTaskToWorker_withMultiEnabled() throws Exception {
        String host = zkContainer.getHost();
        Integer port = zkContainer.getMappedPort(2181);
        String connect = host + ":" + port;

        try (ZkFutures zf = new ZkFutures(connect, 8_000, e -> { /* no-op */ }, Executors.newScheduledThreadPool(1), true)) {
            String tasks = "/tasks";
            String claims = "/claims";
            String assign = "/assign";
            String workerId = "worker1";
            String taskId = "task-it-1";

            // Ensure base paths and worker path
            CompletableFuture.allOf(
                    zf.ensurePersistent(tasks),
                    zf.ensurePersistent(claims),
                    zf.ensurePersistent(assign),
                    zf.ensurePersistent(assign + "/" + workerId)
            ).join();

            // Create one task
            zf.createPersistent(tasks + "/" + taskId, "payload".getBytes(), org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE).join();

            // Start assigner
            assigner = new TasksAssigner(zf, tasks, claims, assign, nd -> workerId);
            assigner.start().join();

            // Await assignment created and task/claim removed
            String assignZ = assign + "/" + workerId + "/" + taskId;
            waitUntilTrue(() -> zf.exists(assignZ, null).join().isPresent(), Duration.ofSeconds(8));
            waitUntilTrue(() -> zf.exists(tasks + "/" + taskId, null).join().isEmpty(), Duration.ofSeconds(5));
            waitUntilTrue(() -> zf.exists(claims + "/" + taskId, null).join().isEmpty(), Duration.ofSeconds(5));
        }
    }

    @Test
    @Timeout(30)
    void compensation_cleansOrphanClaims() throws Exception {
        String host = zkContainer.getHost();
        Integer port = zkContainer.getMappedPort(2181);
        String connect = host + ":" + port;

        try (ZkFutures zf = new ZkFutures(connect, 8_000, e -> { /* no-op */ }, Executors.newScheduledThreadPool(1), false)) {
            String tasks = "/tasks";
            String claims = "/claims";
            String assign = "/assign";

            // Ensure base paths
            CompletableFuture.allOf(
                    zf.ensurePersistent(tasks),
                    zf.ensurePersistent(claims),
                    zf.ensurePersistent(assign)
            ).join();

            // Create orphan claim without task
            zf.createEphemeral(claims + "/orphan1", new byte[0], org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE).join();

            // Start assigner with any picker; multi disabled triggers compensation scheduling
            assigner = new TasksAssigner(zf, tasks, claims, assign, nd -> "worker1");
            assigner.start().join();

            // Manually invoke one compensation run to speed up
            assigner.runCompensation();

            // Wait until orphan claim is removed
            waitUntilTrue(() -> zf.exists(claims + "/orphan1", null).join().isEmpty(), Duration.ofSeconds(8));
        }
    }

    @Test
    @Timeout(30)
    void compensation_reassignsOrphanTasks_nonAtomicPath() throws Exception {
        String host = zkContainer.getHost();
        Integer port = zkContainer.getMappedPort(2181);
        String connect = host + ":" + port;

        try (ZkFutures zf = new ZkFutures(connect, 8_000, e -> { /* no-op */ }, Executors.newScheduledThreadPool(1), false)) {
            String tasks = "/tasks";
            String claims = "/claims";
            String assign = "/assign";
            String workerId = "worker1";
            String taskId = "task-comp-1";

            // Ensure base and worker assign path
            CompletableFuture.allOf(
                    zf.ensurePersistent(tasks),
                    zf.ensurePersistent(claims),
                    zf.ensurePersistent(assign),
                    zf.ensurePersistent(assign + "/" + workerId)
            ).join();

            // Create orphan task without claim
            zf.createPersistent(tasks + "/" + taskId, "payload".getBytes(), org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE).join();

            // Start assigner with picker
            assigner = new TasksAssigner(zf, tasks, claims, assign, nd -> workerId);
            assigner.start().join();

            // Trigger compensation
            assigner.runCompensation();

            String assignZ = assign + "/" + workerId + "/" + taskId;
            // Eventually, assignment should exist and task/claim removed (non-atomic path)
            waitUntilTrue(() -> zf.exists(assignZ, null).join().isPresent(), Duration.ofSeconds(10));
            waitUntilTrue(() -> zf.exists(tasks + "/" + taskId, null).join().isEmpty(), Duration.ofSeconds(10));
            waitUntilTrue(() -> zf.exists(claims + "/" + taskId, null).join().isEmpty(), Duration.ofSeconds(10));
        }
    }

    private static void waitUntilTrue(java.util.function.Supplier<Boolean> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Condition not met within timeout: " + timeout);
    }
}


