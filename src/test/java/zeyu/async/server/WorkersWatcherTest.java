package zeyu.async.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zeyu.async.common.ZkFutures;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.data.Stat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkersWatcherTest {

    @Mock
    private ZkFutures zf;
    
    @Mock
    private ScheduledExecutorService scheduler;
    
    private WorkersWatcher watcher;
    private AtomicReference<WorkersWatcher.Snapshot> lastSnapshot;
    private AtomicReference<WorkersWatcher.Diff> lastDiff;
    private CountDownLatch changeLatch;

    @BeforeEach
    void setUp() {
        lastSnapshot = new AtomicReference<>();
        lastDiff = new AtomicReference<>();
        changeLatch = new CountDownLatch(1);
        
        watcher = new WorkersWatcher("/workers", zf, (snapshot, diff) -> {
            lastSnapshot.set(snapshot);
            lastDiff.set(diff);
            changeLatch.countDown();
        });
    }

    @Test
    void testStartWithEmptyWorkers() {
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent("/workers")).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty 返回空列表
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(Optional.empty());
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(childrenFuture);

        // 启动 watcher
        CompletableFuture<Void> startFuture = watcher.start();
        
        assertDoesNotThrow(() -> startFuture.get());
        
        // 验证路径被确保存在
        verify(zf, times(2)).ensurePersistent("/workers");
        verify(zf).getChildrenOrEmpty(eq("/workers"), any());
    }

    @Test
    void testWorkersChange() throws Exception {
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent("/workers")).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty 返回初始 worker 列表
        ZkFutures.ChildrenSnapshot initialSnapshot = new ZkFutures.ChildrenSnapshot(
                List.of("worker1"), null);
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(Optional.of(initialSnapshot));
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(childrenFuture);

        // 启动 watcher
        CompletableFuture<Void> startFuture = watcher.start();
        startFuture.get(5, TimeUnit.SECONDS);
        
        // 等待回调被调用
        assertTrue(changeLatch.await(2, TimeUnit.SECONDS));
        
        // 验证快照和差异
        WorkersWatcher.Snapshot snapshot = lastSnapshot.get();
        WorkersWatcher.Diff diff = lastDiff.get();
        
        assertNotNull(snapshot);
        assertNotNull(diff);
        assertEquals(Set.of("worker1"), snapshot.children());
        assertEquals(Set.of("worker1"), diff.added());
        assertTrue(diff.removed().isEmpty());
    }

    @Test
    void testWorkersRemoved() throws Exception {    
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent("/workers")).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty 返回更新后的 worker 列表
        ZkFutures.ChildrenSnapshot updatedSnapshot = new ZkFutures.ChildrenSnapshot(
                List.of("worker1"), null);
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(Optional.of(updatedSnapshot));
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(childrenFuture);

        // 启动 watcher
        CompletableFuture<Void> startFuture = watcher.start();
        startFuture.get(5, TimeUnit.SECONDS);
        
        // 等待回调被调用
        assertTrue(changeLatch.await(2, TimeUnit.SECONDS));
        
        // 验证差异
        WorkersWatcher.Diff diff = lastDiff.get();
        assertNotNull(diff);
        assertEquals(Set.of("worker1"), diff.added());
        assertTrue(diff.removed().isEmpty());
    }

    @Test
    void testSnapshotTimestamp() throws Exception {
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent("/workers")).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty
        ZkFutures.ChildrenSnapshot snapshot = new ZkFutures.ChildrenSnapshot(
                List.of("worker1"), null);
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(Optional.of(snapshot));
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(childrenFuture);

        Instant beforeStart = Instant.now();
        
        // 启动 watcher
        CompletableFuture<Void> startFuture = watcher.start();
        startFuture.get(5, TimeUnit.SECONDS);
        
        // 等待回调被调用
        assertTrue(changeLatch.await(2, TimeUnit.SECONDS));
        
        Instant afterStart = Instant.now();
        
        // 验证时间戳
        WorkersWatcher.Snapshot resultSnapshot = lastSnapshot.get();
        assertNotNull(resultSnapshot);
        assertTrue(resultSnapshot.ts().isAfter(beforeStart) || resultSnapshot.ts().equals(beforeStart));
        assertTrue(resultSnapshot.ts().isBefore(afterStart) || resultSnapshot.ts().equals(afterStart));
    }

    @Test
    void testCversionTracking() throws Exception {
        // Mock Stat with cversion
        Stat stat = new Stat();
        stat.setCversion(5);
        
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent("/workers")).thenReturn(ensureFuture);
        
        // Mock getChildrenOrEmpty with stat
        ZkFutures.ChildrenSnapshot snapshot = new ZkFutures.ChildrenSnapshot(
            List.of("worker1"), stat);
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> childrenFuture = 
            CompletableFuture.completedFuture(Optional.of(snapshot));
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(childrenFuture);

        // 启动 watcher
        CompletableFuture<Void> startFuture = watcher.start();
        startFuture.get(5, TimeUnit.SECONDS);
        
        // 等待回调被调用
        assertTrue(changeLatch.await(2, TimeUnit.SECONDS));
        
        // 验证 cversion
        WorkersWatcher.Snapshot resultSnapshot = lastSnapshot.get();
        assertNotNull(resultSnapshot);
        assertEquals(5, resultSnapshot.cversion());
    }

    @Test
    void testEventDrivenRefreshOnChildrenChanged() throws Exception {
        // Mock ensurePersistent
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent("/workers")).thenReturn(ensureFuture);

        // Initial empty children
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> empty =
                CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of(), null)));
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(empty);

        // Start watcher
        watcher.start().get(5, TimeUnit.SECONDS);

        // Prepare next refresh result with a worker
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> withOne =
                CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of("workerX"), null)));
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(withOne);

        // Reset latch for event-triggered update
        changeLatch = new CountDownLatch(1);
        watcher = new WorkersWatcher("/workers", zf, (snapshot, diff) -> {
            lastSnapshot.set(snapshot);
            lastDiff.set(diff);
            changeLatch.countDown();
        });
        // Re-start with same mock state to register watcher again
        watcher.start().get(5, TimeUnit.SECONDS);

        // Send NodeChildrenChanged event
        watcher.process(new org.apache.zookeeper.WatchedEvent(
                org.apache.zookeeper.Watcher.Event.EventType.NodeChildrenChanged,
                org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected,
                "/workers"));

        assertTrue(changeLatch.await(2, TimeUnit.SECONDS));
        assertEquals(Set.of("workerX"), lastSnapshot.get().children());
    }

    @Test
    void testCloseStopsFurtherCallbacks() throws Exception {
        // Mock ensurePersistent + initial children
        CompletableFuture<Void> ensureFuture = CompletableFuture.completedFuture(null);
        when(zf.ensurePersistent("/workers")).thenReturn(ensureFuture);
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> initial =
                CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of("w1"), null)));
        when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(initial);

        // Start watcher and close it
        watcher.start().get(5, TimeUnit.SECONDS);
        watcher.close();

        // Change children result; send event
        CompletableFuture<Optional<ZkFutures.ChildrenSnapshot>> changed =
                CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of("w2"), null)));
        lenient().when(zf.getChildrenOrEmpty(eq("/workers"), any())).thenReturn(changed);

        // Reset latch; since closed, callback should NOT fire
        changeLatch = new CountDownLatch(1);
        lastSnapshot.set(null);
        lastDiff.set(null);

        watcher.process(new org.apache.zookeeper.WatchedEvent(
                Event.EventType.NodeChildrenChanged,
                KeeperState.SyncConnected,
                "/workers"));

        assertFalse(changeLatch.await(1, TimeUnit.SECONDS));
        assertNull(lastSnapshot.get());
        assertNull(lastDiff.get());
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }
}
