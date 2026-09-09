package com.plexon.shops.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedExecutorTest {
    @Test
    void exposesQueuePressureRejectionsAndCompletionMetrics() throws Exception {
        BoundedExecutor executor = new BoundedExecutor("bounded-test", 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var first = executor.run(() -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            var second = executor.run(() -> await(release));
            var rejected = executor.run(() -> { });

            CompletionException rejection = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class,
                    rejected::join
            );
            assertInstanceOf(RejectedExecutionException.class, rejection.getCause());

            BoundedExecutor.ExecutorMetrics pressured = executor.metrics();
            assertEquals(1, pressured.queueDepth());
            assertEquals(1, pressured.queueCapacity());
            assertEquals(1, pressured.activeThreads());
            assertEquals(3L, pressured.submittedOperations());
            assertEquals(1L, pressured.rejectedOperations());

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            BoundedExecutor.ExecutorMetrics drained = executor.metrics();
            assertEquals(2L, drained.completedOperations());
            assertEquals(0, drained.queueDepth());
            assertTrue(drained.p95TaskLatencyMillis() >= 0.0D);
        } finally {
            release.countDown();
            executor.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
