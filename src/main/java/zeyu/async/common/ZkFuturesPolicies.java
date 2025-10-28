package zeyu.async.common;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Optional policies for decorating ZkFutures with retry and circuit breaker.
 */
public final class ZkFuturesPolicies {
    public static final class RetryPolicy {
        public final int maxRetries;
        public final Duration baseBackoff;
        public final Class<?>[] retryOn;

        public RetryPolicy(int maxRetries, Duration baseBackoff, Class<?>... retryOn) {
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries >= 0");
            this.maxRetries = maxRetries;
            this.baseBackoff = Objects.requireNonNull(baseBackoff);
            this.retryOn = retryOn;
        }

        public <T> CompletableFuture<T> retryAsync(Supplier<CompletableFuture<T>> op, ScheduledExecutorService scheduler) {
            CompletableFuture<T> cf = new CompletableFuture<>();
            attempt(op, 0, scheduler, cf);
            return cf;
        }

        private <T> void attempt(Supplier<CompletableFuture<T>> op, int n, ScheduledExecutorService scheduler, CompletableFuture<T> sink) {
            try {
                op.get().whenComplete((v, e) -> {
                    if (e == null) {
                        sink.complete(v);
                    } else if (n < maxRetries && shouldRetry(e)) {
                        long delay = (long) (baseBackoff.toMillis() * Math.pow(2, n)
                                * (0.5 + ThreadLocalRandom.current().nextDouble()));
                        scheduler.schedule(() -> attempt(op, n + 1, scheduler, sink), delay, TimeUnit.MILLISECONDS);
                    } else {
                        sink.completeExceptionally(e);
                    }
                });
            } catch (Throwable t) {
                if (n < maxRetries && shouldRetry(t)) {
                    long delay = (long) (baseBackoff.toMillis() * Math.pow(2, n)
                            * (0.5 + ThreadLocalRandom.current().nextDouble()));
                    scheduler.schedule(() -> attempt(op, n + 1, scheduler, sink), delay, TimeUnit.MILLISECONDS);
                } else {
                    sink.completeExceptionally(t);
                }
            }
        }

        private boolean shouldRetry(Throwable e) {
            Throwable cause = unwrap(e);
            for (Class<?> retry : retryOn) {
                if (retry.isAssignableFrom(cause.getClass())) {
                    return true;
                }
            }
            return false;
        }

        private static Throwable unwrap(Throwable e) {
            if (e instanceof CompletionException ce && ce.getCause() != null) {
                return ce.getCause();
            }
            return e;
        }
    }

    public static final class TimeoutPolicy {
        public final Duration timeout;
        public final ScheduledExecutorService scheduler;

        public TimeoutPolicy(Duration timeout, ScheduledExecutorService scheduler) {
            this.timeout = Objects.requireNonNull(timeout);
            this.scheduler = Objects.requireNonNull(scheduler);
        }

        public <T> CompletableFuture<T> withTimeout(CompletableFuture<T> cf) {
            final CompletableFuture<T> timeout = new CompletableFuture<>();
            ScheduledFuture<?> task = scheduler.schedule(() -> timeout.completeExceptionally(new TimeoutException()),
                    this.timeout.toMillis(), TimeUnit.MILLISECONDS);

            cf.whenComplete((r, t) -> task.cancel(false));
            return cf.applyToEither(timeout, x -> x);
        }
    }

    public final CircuitBreaker circuitBreaker; // nullable
    public final RetryPolicy retryPolicy;       // nullable
    public final TimeoutPolicy timeoutPolicy;   // nullable
    public final ScheduledExecutorService scheduler; // required when retryPolicy != null

    private ZkFuturesPolicies(CircuitBreaker cb, RetryPolicy rp, TimeoutPolicy tp, ScheduledExecutorService scheduler) {
        this.circuitBreaker = cb;
        this.retryPolicy = rp;
        this.timeoutPolicy = tp;
        this.scheduler = scheduler;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CircuitBreaker cb;
        private RetryPolicy rp;
        private TimeoutPolicy tp;
        private ScheduledExecutorService scheduler;

        public Builder circuitBreaker(CircuitBreaker cb) {
            this.cb = cb; return this;
        }

        public Builder retry(RetryPolicy rp, ScheduledExecutorService scheduler) {
            this.rp = rp; this.scheduler = scheduler; return this;
        }

        public Builder timeout(TimeoutPolicy tp) {
            this.tp = tp; return this;
        }

        public ZkFuturesPolicies build() { return new ZkFuturesPolicies(cb, rp, tp, scheduler); }
    }
}


