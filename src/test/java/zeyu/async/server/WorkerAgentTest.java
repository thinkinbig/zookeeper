package zeyu.async.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zeyu.async.common.ZkFutures;

import org.awaitility.Awaitility;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerAgentTest {

    @Mock
    private ZkFutures zf;

    @Mock
    private ScheduledExecutorService scheduler;

    private WorkerAgent agent;

    @BeforeEach
    void setUp() {
        AtomicReference<ZkFutures.NodeData> handled = new AtomicReference<>();
        agent = new WorkerAgent(
                zf,
                "w1",
                "/workers",
                "/assign",
                "/status",
                handled::set
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (agent != null) agent.close();
    }

    @Test
    void testStartRegistersPresenceAndInitialRefresh() {
        when(zf.ensurePersistent(eq("/workers"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.createEphemeral(eq("/workers/w1"), any(), any())).thenReturn(CompletableFuture.completedFuture("/workers/w1"));
        when(zf.ensurePersistent(eq("/status"))).thenReturn(CompletableFuture.completedFuture(null));

        // initial refresh
        when(zf.ensurePersistent(eq("/assign/w1"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.getChildrenOrEmpty(eq("/assign/w1"), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of(), null))));

        assertDoesNotThrow(() -> agent.start().get(5, TimeUnit.SECONDS));

        verify(zf).ensurePersistent("/workers");
        verify(zf).createEphemeral(eq("/workers/w1"), any(), any());
        verify(zf).ensurePersistent("/status");
        verify(zf).ensurePersistent("/assign/w1");
        verify(zf).getChildrenOrEmpty(eq("/assign/w1"), any());
    }

    @Test
    void testProcessTaskSuccessFlow() throws Exception {
        // start + empty first
        when(zf.ensurePersistent(eq("/workers"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.createEphemeral(eq("/workers/w1"), any(), any())).thenReturn(CompletableFuture.completedFuture("/workers/w1"));
        when(zf.ensurePersistent(eq("/status"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.ensurePersistent(eq("/assign/w1"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.getChildrenOrEmpty(eq("/assign/w1"), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of(), null))));
        agent.start().get(5, TimeUnit.SECONDS);

        // next refresh contains a task
        when(zf.ensurePersistent(eq("/assign/w1"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.getChildrenOrEmpty(eq("/assign/w1"), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of("t1"), null))));

        ZkFutures.NodeData nd = new ZkFutures.NodeData("payload".getBytes(), null);
        when(zf.getData(eq("/assign/w1/t1"), isNull())).thenReturn(CompletableFuture.completedFuture(nd));
        when(zf.setData(eq("/status/t1"), any(), eq(-1))).thenReturn(CompletableFuture.completedFuture(new org.apache.zookeeper.data.Stat()));
        when(zf.delete(eq("/assign/w1/t1"), eq(-1))).thenReturn(CompletableFuture.completedFuture(null));

        // trigger children changed event
        agent.process(new org.apache.zookeeper.WatchedEvent(
                org.apache.zookeeper.Watcher.Event.EventType.NodeChildrenChanged,
                org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected,
                "/assign/w1"));

        // verify side-effects (async) with await
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            verify(zf, atLeastOnce()).getChildrenOrEmpty(eq("/assign/w1"), any());
            verify(zf).getData(eq("/assign/w1/t1"), isNull());
            verify(zf).setData(eq("/status/t1"), any(), eq(-1));
            verify(zf).delete(eq("/assign/w1/t1"), eq(-1));
        });
    }

    @Test
    void testCloseStopsProcessing() throws Exception {
        when(zf.ensurePersistent(eq("/workers"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.createEphemeral(eq("/workers/w1"), any(), any())).thenReturn(CompletableFuture.completedFuture("/workers/w1"));
        when(zf.ensurePersistent(eq("/status"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.ensurePersistent(eq("/assign/w1"))).thenReturn(CompletableFuture.completedFuture(null));
        when(zf.getChildrenOrEmpty(eq("/assign/w1"), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(new ZkFutures.ChildrenSnapshot(List.of(), null))));
        agent.start().get(5, TimeUnit.SECONDS);

        agent.close();

        // after close, event should not trigger refresh
        reset(zf);
        agent.process(new org.apache.zookeeper.WatchedEvent(
                org.apache.zookeeper.Watcher.Event.EventType.NodeChildrenChanged,
                org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected,
                "/assign/w1"));
        // no interactions expected after close
        verifyNoInteractions(zf);
    }
}


