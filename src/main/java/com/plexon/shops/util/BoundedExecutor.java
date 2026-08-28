package com.plexon.shops.util;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Fixed-size worker with a bounded queue so background load cannot grow without limit. */
public final class BoundedExecutor implements AutoCloseable {
    private final ThreadPoolExecutor delegate;

    public BoundedExecutor(String threadPrefix, int threads, int queueCapacity) {
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
        try {
            return CompletableFuture.supplyAsync(supplier, delegate);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Void> run(Runnable task) {
        return supply(() -> {
            task.run();
            return null;
        });
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
}
