package zeyu.async.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zeyu.async.common.ZkFutures;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.time.Duration;
import org.awaitility.Awaitility;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TasksAssignerTest {

    @Mock
    private ZkFutures zf;
    
    @Mock
    private TasksAssigner assigner;

    @BeforeEach
    void setUp() {
        Function<ZkFutures.NodeData, String> pickWorker = data -> "worker1"; // 总是选择 worker1

        assigner = new TasksAssigner(zf, "/tasks", "/claims", "/assign", pickWorker);
        
        // 是否启用 multi 由各测试分别设置
    }

    @Test
    void testCompensationCleansOrphanClaims() {
        // claims: [taskX], but tasks/taskX missing -> should delete claims/taskX
        when(zf.ensurePersistent(eq("/claims"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.ensurePersistent(eq("/tasks"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.ensurePersistent(eq("/assign"))).thenReturn(CompletableFuture.completedFuture(null));
        ZkFutures.ChildrenSnapshot claimsSnap = new ZkFutures.ChildrenSnapshot(List.of("taskX"), null);
        when(zf.getChildrenOrEmpty(eq("/claims"), isNull())).thenReturn(CompletableFuture.completedFuture(Optional.of(claimsSnap)));
        when(zf.exists(eq("/tasks/taskX"), isNull())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(zf.delete(eq("/claims/taskX"), eq(-1))).thenReturn(CompletableFuture.completedFuture(null));

        // trigger compensation
        assigner.runCompensation();

        // verify delete eventually called (compensation runs async)
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(
            () -> verify(zf).delete(eq("/claims/taskX"), eq(-1))
        );
    }

    @Test
    void testCompensationReassignsOrphanTasks() {
        // tasks: [taskY], but claims/taskY missing -> should try claimThenAssign(taskY)
        when(zf.ensurePersistent(eq("/tasks"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.ensurePersistent(eq("/claims"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.ensurePersistent(eq("/assign"))).thenReturn(CompletableFuture.completedFuture(null));
        ZkFutures.ChildrenSnapshot tasksSnap = new ZkFutures.ChildrenSnapshot(List.of("taskY"), null);
        when(zf.getChildrenOrEmpty(eq("/tasks"), isNull())).thenReturn(CompletableFuture.completedFuture(Optional.of(tasksSnap)));
        when(zf.getChildrenOrEmpty(eq("/claims"), isNull())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(zf.exists(eq("/claims/taskY"), isNull())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        // claimThenAssign pipeline stubs
        when(zf.createEphemeral(eq("/claims/taskY"), any(), any())).thenReturn(CompletableFuture.completedFuture("/claims/taskY"));
        when(zf.getData(eq("/tasks/taskY"), any())).thenReturn(CompletableFuture.completedFuture(new ZkFutures.NodeData("data".getBytes(), null)));
        when(zf.isMultiEnabled()).thenReturn(true);
        when(zf.multi(any())).thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        // no extra delete stubbing needed for multi path

        // trigger compensation
        assigner.runCompensation();

        // verify we attempted to claim taskY
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(
            () -> verify(zf).createEphemeral(eq("/claims/taskY"), any(), any())
        );
    }
    @Test
    void testStart() {
        when(zf.isMultiEnabled()).thenReturn(true);
        // Mock ensurePersistent 调用
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent(anyString())).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty 返回空列表
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(Optional.empty());
        when(zf.getChildrenOrEmpty(eq("/tasks"), any())).thenReturn(childrenFuture);

        // 启动分配器
        CompletableFuture<Void> startFuture = assigner.start();
        
        assertDoesNotThrow(() -> startFuture.get());
        
        // 验证路径被确保存在
        verify(zf).ensurePersistent("/tasks");
        verify(zf).ensurePersistent("/claims");
        verify(zf).ensurePersistent("/assign");
    }

    @Test
    void testTaskAssignment() {
        when(zf.isMultiEnabled()).thenReturn(true);
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent(anyString())).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty 返回一个任务
        ZkFutures.ChildrenSnapshot taskSnapshot = new ZkFutures.ChildrenSnapshot(
                List.of("task1"), null);
        CompletableFuture<java.util.Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(java.util.Optional.of(taskSnapshot));
        when(zf.getChildrenOrEmpty(eq("/tasks"), any())).thenReturn(childrenFuture);
        
        // Mock createEphemeral for claim
        CompletableFuture<String> createClaimFuture = CompletableFuture.completedFuture("/claims/task1");
        when(zf.createEphemeral(eq("/claims/task1"), any(), any())).thenReturn(createClaimFuture);
        
        // Mock getData for task
        ZkFutures.NodeData taskData = new ZkFutures.NodeData("task-data".getBytes(), null);
        CompletableFuture<ZkFutures.NodeData> getDataFuture = CompletableFuture.completedFuture(taskData);
        when(zf.getData(eq("/tasks/task1"), any())).thenReturn(getDataFuture);
        
        // Mock multi operation
        CompletableFuture<List<org.apache.zookeeper.OpResult>> multiFuture = 
            CompletableFuture.completedFuture(Collections.emptyList());
        when(zf.multi(any())).thenReturn(multiFuture);

        // 启动分配器
        CompletableFuture<Void> startFuture = assigner.start();
        
        assertDoesNotThrow(() -> startFuture.get());
        
        // 验证任务被处理
        verify(zf).createEphemeral(eq("/claims/task1"), any(), any());
        verify(zf).getData(eq("/tasks/task1"), any());
        verify(zf).multi(any()); // 验证 multi 操作被调用
    }

    @Test
    void testNoAvailableWorker() {
        when(zf.isMultiEnabled()).thenReturn(true);
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent(anyString())).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty 返回一个任务
        ZkFutures.ChildrenSnapshot taskSnapshot = new ZkFutures.ChildrenSnapshot(
                List.of("task1"), null);
        CompletableFuture<java.util.Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(java.util.Optional.of(taskSnapshot));
        when(zf.getChildrenOrEmpty(eq("/tasks"), any())).thenReturn(childrenFuture);
        
        // Mock createEphemeral for claim
        CompletableFuture<String> createClaimFuture = CompletableFuture.completedFuture("/claims/task1");
        when(zf.createEphemeral(eq("/claims/task1"), any(), any())).thenReturn(createClaimFuture);
        
        // Mock getData for task
        ZkFutures.NodeData taskData = new ZkFutures.NodeData("task-data".getBytes(), null);
        CompletableFuture<ZkFutures.NodeData> getDataFuture = CompletableFuture.completedFuture(taskData);
        when(zf.getData(eq("/tasks/task1"), any())).thenReturn(getDataFuture);
        
        // Mock delete for claim release
        CompletableFuture<Void> deleteFuture = CompletableFuture.completedFuture(null);
        when(zf.delete(eq("/claims/task1"), eq(-1))).thenReturn(deleteFuture);

        // 使用返回 null 的 pickWorker（try-with-resources 确保关闭）
        try (TasksAssigner assignerNoWorker = new TasksAssigner(zf, "/tasks", "/claims", "/assign",
                data -> null)) { // 没有可用 worker
            // 启动分配器
            CompletableFuture<Void> startFuture = assignerNoWorker.start();
            assertDoesNotThrow(() -> startFuture.get());
        }
        // 验证 claim 被释放
        verify(zf).delete(eq("/claims/task1"), eq(-1));
    }

    @Test
    void testCompensationMode() {
        // Mock multi 未启用
        when(zf.isMultiEnabled()).thenReturn(false);
        
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent(anyString())).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty 返回空列表
        ZkFutures.ChildrenSnapshot emptySnapshot = new ZkFutures.ChildrenSnapshot(
            Collections.emptyList(), null);
        CompletableFuture<java.util.Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(java.util.Optional.of(emptySnapshot));
        when(zf.getChildrenOrEmpty(eq("/tasks"), any())).thenReturn(childrenFuture);

        // 启动分配器
        CompletableFuture<Void> startFuture = assigner.start();
        
        assertDoesNotThrow(() -> startFuture.get());
        
        // 验证 multi 未启用时，补偿模式被激活
        // 注意：TasksAssigner 使用自己的 compensationScheduler，不是传入的 mock scheduler
        // 这里我们验证 isMultiEnabled() 被调用来决定是否启动补偿任务
        verify(zf).isMultiEnabled();
    }

    @AfterEach
    void tearDown() {
        if (assigner != null) {
            assigner.close();
        }
    }
}
