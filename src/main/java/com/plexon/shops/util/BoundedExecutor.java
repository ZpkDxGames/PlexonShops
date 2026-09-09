package com.plexon.shops.util;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Fixed-size worker with a bounded queue so background load cannot grow without limit. */
public final class BoundedExecutor implements AutoCloseable {
    private static final int LATENCY_SAMPLE_CAPACITY = 256;

    private final ThreadPoolExecutor delegate;
    private final int queueCapacity;
    private final AtomicLong submittedOperations = new AtomicLong();
    private final AtomicLong completedOperations = new AtomicLong();
    private final AtomicLong rejectedOperations = new AtomicLong();
    private final Object latencyLock = new Object();
    private final long[] latencySamplesNanos = new long[LATENCY_SAMPLE_CAPACITY];
    private int latencySampleIndex;
    private int latencySampleCount;

    public BoundedExecutor(String threadPrefix, int threads, int queueCapacity) {
        this.queueCapacity = queueCapacity;
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, threadPrefix + '-' + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.delegate = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.delegate.allowCoreThreadTimeOut(false);
    }

    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        TimedTask<T> task = new TimedTask<>(supplier, future, System.nanoTime());
        submittedOperations.incrementAndGet();
        try {
            delegate.execute(task);
        } catch (RejectedExecutionException error) {
            rejectedOperations.incrementAndGet();
            future.completeExceptionally(error);
        }
        return future;
    }

    public CompletableFuture<Void> run(Runnable task) {
        return supply(() -> {
            task.run();
            return null;
        });
    }

    /** Snapshot intended for diagnostics/status commands, not per-tick polling. */
    public ExecutorMetrics metrics() {
        Runnable oldest = delegate.getQueue().peek();
        long oldestQueuedAgeNanos = oldest instanceof TimedTask<?> timed
                ? Math.max(0L, System.nanoTime() - timed.enqueuedAtNanos)
                : 0L;
        return new ExecutorMetrics(
                delegate.getQueue().size(),
                queueCapacity,
                delegate.getActiveCount(),
                rejectedOperations.get(),
                submittedOperations.get(),
                completedOperations.get(),
                percentile95Millis(),
                nanosToMillis(oldestQueuedAgeNanos)
        );
    }

    public boolean shutdown(Duration timeout) {
        delegate.shutdown();
        try {
            if (delegate.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            delegate.shutdownNow();
            return delegate.awaitTermination(Math.min(timeout.toMillis(), 2_000L), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            delegate.shutdownNow();
            return false;
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(10));
    }

    private void recordLatency(long latencyNanos) {
        synchronized (latencyLock) {
            latencySamplesNanos[latencySampleIndex] = Math.max(0L, latencyNanos);
            latencySampleIndex = (latencySampleIndex + 1) % latencySamplesNanos.length;
            latencySampleCount = Math.min(latencySampleCount + 1, latencySamplesNanos.length);
        }
    }

    private double percentile95Millis() {
        long[] copy;
        synchronized (latencyLock) {
            if (latencySampleCount == 0) {
                return 0.0D;
            }
            copy = Arrays.copyOf(latencySamplesNanos, latencySampleCount);
        }
        Arrays.sort(copy);
        int index = Math.min(copy.length - 1, (int) Math.ceil(copy.length * 0.95D) - 1);
        return copy[index] / 1_000_000.0D;
    }

    private long nanosToMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    public record ExecutorMetrics(
            int queueDepth,
            int queueCapacity,
            int activeThreads,
            long rejectedOperations,
            long submittedOperations,
            long completedOperations,
            double p95TaskLatencyMillis,
            long oldestQueuedTaskAgeMillis
    ) {
    }

    private final class TimedTask<T> implements Runnable {
        private final Supplier<T> supplier;
        private final CompletableFuture<T> future;
        private final long enqueuedAtNanos;

        private TimedTask(Supplier<T> supplier, CompletableFuture<T> future, long enqueuedAtNanos) {
            this.supplier = supplier;
            this.future = future;
            this.enqueuedAtNanos = enqueuedAtNanos;
        }

        @Override
        public void run() {
            try {
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            } finally {
                completedOperations.incrementAndGet();
                recordLatency(System.nanoTime() - enqueuedAtNanos);
            }
        }
    }
}
