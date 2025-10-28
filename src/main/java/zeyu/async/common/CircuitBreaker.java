package zeyu.async.common;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A lightweight, standalone circuit breaker that can wrap arbitrary async operations.
 *
 * Usage:
 *   CircuitBreaker cb = CircuitBreaker.builder()
 *       .failureThreshold(3)
 *       .openSleepWindowMs(5000)
 *       .halfOpenMaxInFlight(2)
 *       .build();
 *   cb.execute(() -> zf.getData(path, null));
 *
 * Not wired into ZkFutures by default; callers compose explicitly, similar to retry.
 */
public class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State state = State.CLOSED;
    private final Object lock = new Object();
    private volatile long openedAtMs = 0L;
    private volatile int consecutiveFailures = 0;
    private volatile int halfOpenInFlight = 0;

    // Defaults; callers may use setters to tune
    private volatile int failureThreshold = 5;          // N consecutive failures → OPEN
    private volatile long openSleepWindowMs = 2_000;    // hold OPEN for this window
    private volatile int halfOpenMaxInFlight = 1;       // probes allowed when HALF_OPEN

    public State getState() { return state; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public long getOpenedAtMs() { return openedAtMs; }

    public void setFailureThreshold(int failureThreshold) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold > 0");
        this.failureThreshold = failureThreshold;
    }

    public void setOpenSleepWindowMs(long openSleepWindowMs) {
        if (openSleepWindowMs < 0) throw new IllegalArgumentException("openSleepWindowMs >= 0");
        this.openSleepWindowMs = openSleepWindowMs;
    }

    public void setHalfOpenMaxInFlight(int halfOpenMaxInFlight) {
        if (halfOpenMaxInFlight <= 0) throw new IllegalArgumentException("halfOpenMaxInFlight > 0");
        this.halfOpenMaxInFlight = halfOpenMaxInFlight;
    }

    /**
     * Execute an async supplier under circuit breaker protection.
     * If breaker is OPEN and sleep window not elapsed, completes exceptionally immediately.
     */
    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> op) {
        Objects.requireNonNull(op, "op");
        long now = System.currentTimeMillis();

        if (state == State.OPEN) {
            if (now - openedAtMs < openSleepWindowMs) {
                CompletableFuture<T> rejected = new CompletableFuture<>();
                rejected.completeExceptionally(new RejectedExecutionException("circuit-open"));
                return rejected;
            }
            synchronized (lock) {
                if (state == State.OPEN && now - openedAtMs >= openSleepWindowMs) {
                    state = State.HALF_OPEN;
                    halfOpenInFlight = 0;
                }
            }
        }

        if (state == State.HALF_OPEN) {
            synchronized (lock) {
                if (halfOpenInFlight >= halfOpenMaxInFlight) {
                    CompletableFuture<T> rejected = new CompletableFuture<>();
                    rejected.completeExceptionally(new RejectedExecutionException("circuit-half-open-limit"));
                    return rejected;
                }
                halfOpenInFlight++;
            }
        }

        CompletableFuture<T> cf;
        try {
            cf = op.get();
        } catch (Throwable t) {
            cf = new CompletableFuture<>();
            cf.completeExceptionally(t);
        }

        cf.whenComplete((r, e) -> {
            synchronized (lock) {
                if (state == State.HALF_OPEN) {
                    halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
                }
                if (e == null) {
                    consecutiveFailures = 0;
                    state = State.CLOSED;
                } else {
                    consecutiveFailures++;
                    if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
                        state = State.OPEN;
                        openedAtMs = System.currentTimeMillis();
                    }
                }
            }
        });

        return cf;
    }

    /**
     * Convenience: schedule a forced close after window using an external scheduler.
     * Caller may use this to ensure the breaker eventually probes again even if time never advances in tests.
     */
    public void scheduleCloseProbe(ScheduledExecutorService scheduler) {
        if (scheduler == null) return;
        scheduler.schedule(() -> {
            synchronized (lock) {
                if (state == State.OPEN && System.currentTimeMillis() - openedAtMs >= openSleepWindowMs) {
                    state = State.HALF_OPEN;
                    halfOpenInFlight = 0;
                }
            }
        }, Math.max(1, openSleepWindowMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Builder for CircuitBreaker configuration.
     * Usage:
     *   CircuitBreaker cb = CircuitBreaker.builder()
     *       .failureThreshold(3)
     *       .openSleepWindowMs(5000)
     *       .halfOpenMaxInFlight(2)
     *       .build();
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int failureThreshold = 5;
        private long openSleepWindowMs = 2_000;
        private int halfOpenMaxInFlight = 1;

        public Builder failureThreshold(int failureThreshold) {
            if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold > 0");
            this.failureThreshold = failureThreshold;
            return this;
        }

        public Builder openSleepWindowMs(long openSleepWindowMs) {
            if (openSleepWindowMs < 0) throw new IllegalArgumentException("openSleepWindowMs >= 0");
            this.openSleepWindowMs = openSleepWindowMs;
            return this;
        }

        public Builder halfOpenMaxInFlight(int halfOpenMaxInFlight) {
            if (halfOpenMaxInFlight <= 0) throw new IllegalArgumentException("halfOpenMaxInFlight > 0");
            this.halfOpenMaxInFlight = halfOpenMaxInFlight;
            return this;
        }

        public CircuitBreaker build() {
            CircuitBreaker cb = new CircuitBreaker();
            cb.setFailureThreshold(failureThreshold);
            cb.setOpenSleepWindowMs(openSleepWindowMs);
            cb.setHalfOpenMaxInFlight(halfOpenMaxInFlight);
            return cb;
        }
    }
}


