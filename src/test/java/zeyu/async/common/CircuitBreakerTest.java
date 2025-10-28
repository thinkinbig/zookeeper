package zeyu.async.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.builder()
                .failureThreshold(2)
                .openSleepWindowMs(1000)
                .halfOpenMaxInFlight(1)
                .build();
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @Test
    @Timeout(10)
    void testCircuitBreakerStates() throws Exception {
        // Initially CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        // Create a failing operation
        AtomicInteger callCount = new AtomicInteger(0);
        Supplier<CompletableFuture<String>> failingOp = () -> {
            callCount.incrementAndGet();
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("Simulated failure"));
            return future;
        };

        // First failure - still CLOSED
        CompletableFuture<String> result1 = circuitBreaker.execute(failingOp);
        assertTrue(result1.isCompletedExceptionally());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(1, callCount.get());

        // Second failure - should trip to OPEN
        CompletableFuture<String> result2 = circuitBreaker.execute(failingOp);
        assertTrue(result2.isCompletedExceptionally());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2, callCount.get());

        // Third call - should be rejected immediately without calling the operation
        CompletableFuture<String> result3 = circuitBreaker.execute(failingOp);
        assertTrue(result3.isCompletedExceptionally());
        try {
            result3.get();
            fail("Should have thrown exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("circuit-open"));
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2, callCount.get()); // No additional calls

        // Wait for circuit breaker to go to HALF_OPEN
        circuitBreaker.scheduleCloseProbe(scheduler);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
        });

        // Test HALF_OPEN state - should allow one probe
        CompletableFuture<String> result4 = circuitBreaker.execute(failingOp);
        assertTrue(result4.isCompletedExceptionally());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState()); // Failed probe goes back to OPEN
        assertEquals(3, callCount.get());

        // Test successful operation in HALF_OPEN
        circuitBreaker.scheduleCloseProbe(scheduler);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
        });

        Supplier<CompletableFuture<String>> successOp = () -> CompletableFuture.completedFuture("success");
        CompletableFuture<String> result5 = circuitBreaker.execute(successOp);
        assertEquals("success", result5.get());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    @Timeout(10)
    void testHalfOpenMaxInFlight() throws Exception {
        // Trip the circuit breaker
        Supplier<CompletableFuture<String>> failingOp = () -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("Failure"));
            return future;
        };

        circuitBreaker.execute(failingOp);
        circuitBreaker.execute(failingOp);
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for HALF_OPEN
        circuitBreaker.scheduleCloseProbe(scheduler);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
        });

        // First call should be allowed
        Supplier<CompletableFuture<String>> slowOp = () -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            scheduler.schedule(() -> future.complete("slow"), 100, TimeUnit.MILLISECONDS);
            return future;
        };

        CompletableFuture<String> result1 = circuitBreaker.execute(slowOp);
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        // Second call should be rejected due to max in-flight limit
        CompletableFuture<String> result2 = circuitBreaker.execute(slowOp);
        assertTrue(result2.isCompletedExceptionally());
        try {
            result2.get();
            fail("Should have thrown exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("circuit-half-open-limit"));
        }

        // Wait for first call to complete
        assertEquals("slow", result1.get());
        
        // Wait a bit for state transition to complete
        await().atMost(Duration.ofMillis(500)).untilAsserted(() -> {
            assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        });
    }

    @Test
    @Timeout(10)
    void testSuccessfulOperationResetsFailures() throws Exception {
        // Create a mixed success/failure operation
        AtomicInteger callCount = new AtomicInteger(0);
        Supplier<CompletableFuture<String>> mixedOp = () -> {
            int count = callCount.incrementAndGet();
            CompletableFuture<String> future = new CompletableFuture<>();
            if (count <= 2) {
                future.completeExceptionally(new RuntimeException("Failure " + count));
            } else {
                future.complete("Success " + count);
            }
            return future;
        };

        // First two calls fail
        circuitBreaker.execute(mixedOp);
        circuitBreaker.execute(mixedOp);
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for HALF_OPEN
        circuitBreaker.scheduleCloseProbe(scheduler);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
        });

        // Third call succeeds - should reset to CLOSED
        CompletableFuture<String> result = circuitBreaker.execute(mixedOp);
        assertEquals("Success 3", result.get());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getConsecutiveFailures());
    }

    @Test
    void testConfigurationSetters() {
        CircuitBreaker cb = new CircuitBreaker();
        
        // Test valid configurations
        assertDoesNotThrow(() -> cb.setFailureThreshold(5));
        assertDoesNotThrow(() -> cb.setOpenSleepWindowMs(2000));
        assertDoesNotThrow(() -> cb.setHalfOpenMaxInFlight(3));
        
        // Test invalid configurations
        assertThrows(IllegalArgumentException.class, () -> cb.setFailureThreshold(0));
        assertThrows(IllegalArgumentException.class, () -> cb.setFailureThreshold(-1));
        assertThrows(IllegalArgumentException.class, () -> cb.setOpenSleepWindowMs(-1));
        assertThrows(IllegalArgumentException.class, () -> cb.setHalfOpenMaxInFlight(0));
        assertThrows(IllegalArgumentException.class, () -> cb.setHalfOpenMaxInFlight(-1));
    }

    @Test
    @Timeout(10)
    void testConcurrentAccess() throws Exception {
        // Test that circuit breaker is thread-safe
        Supplier<CompletableFuture<String>> failingOp = () -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("Concurrent failure"));
            return future;
        };

        // Submit multiple concurrent operations
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[10];
        for (int i = 0; i < 10; i++) {
            futures[i] = circuitBreaker.execute(failingOp)
                    .handle((result, throwable) -> null);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

        // Circuit breaker should be OPEN
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }
}
