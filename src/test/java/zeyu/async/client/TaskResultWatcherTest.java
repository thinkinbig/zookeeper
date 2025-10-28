package zeyu.async.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zeyu.async.common.ZkFutures;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zookeeper.data.Stat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@ExtendWith(MockitoExtension.class)
class TaskResultWatcherTest {

    @Mock
    private ZkFutures zf;

    private ScheduledExecutorService scheduler;
    private TaskResultWatcher watcher;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        watcher = new TaskResultWatcher(zf, "/status");
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) watcher.close();
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Test
    void testStartEnsuresStatusPathAndAwaitsResult() throws Exception {
        // start ensures status path
        when(zf.ensurePersistent(eq("/status"))).thenReturn(CompletableFuture.completedFuture(null));
        watcher.start().get(5, TimeUnit.SECONDS);
        verify(zf).ensurePersistent("/status");

        // arrange exists -> present, then getData returns payload
        String path = "/status/t1";
        when(zf.exists(eq(path), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(new Stat())));
        byte[] payload = "OK".getBytes(StandardCharsets.UTF_8);
        when(zf.getData(eq(path), any())).thenReturn(CompletableFuture.completedFuture(new ZkFutures.NodeData(payload, new Stat())));

        AtomicReference<ZkFutures.NodeData> callbackData = new AtomicReference<>();
        var future = watcher.await(path, callbackData::set);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertTrue(future.isDone()));
        assertEquals(Optional.of("OK"), future.get(1, TimeUnit.SECONDS));
        assertNotNull(callbackData.get());
        assertArrayEquals(payload, callbackData.get().data());
    }

    @Test
    void testAwaitReturnsEmptyIfClosed() throws Exception {
        watcher.close();
        var fut = watcher.await("/status/x", nd -> {});
        assertTrue(fut.isDone());
        assertEquals(Optional.empty(), fut.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testRearmOnSyncConnectedEvent() throws Exception {
        when(zf.ensurePersistent(eq("/status"))).thenReturn(CompletableFuture.completedFuture(null));
        watcher.start().get(5, TimeUnit.SECONDS);

        String path = "/status/t2";
        // first exists present triggers getData later
        when(zf.exists(eq(path), any())).thenReturn(CompletableFuture.completedFuture(Optional.of(new Stat())));
        byte[] payload = "DONE".getBytes(StandardCharsets.UTF_8);
        when(zf.getData(eq(path), any())).thenReturn(CompletableFuture.completedFuture(new ZkFutures.NodeData(payload, new Stat())));

        var fut = watcher.await(path, null);

        // simulate SyncConnected to rearm
        watcher.process(new org.apache.zookeeper.WatchedEvent(
                org.apache.zookeeper.Watcher.Event.EventType.None,
                org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected,
                null));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertTrue(fut.isDone()));
        assertEquals(Optional.of("DONE"), fut.get(1, TimeUnit.SECONDS));
    }
}


