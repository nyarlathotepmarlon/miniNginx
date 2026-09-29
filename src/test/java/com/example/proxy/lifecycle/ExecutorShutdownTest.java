package com.example.proxy.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExecutorShutdownTest {
    @Test
    void cancelsBlockedTaskAndDiscardsQueuedWorkAfterGracePeriod() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            executor.submit(() -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException failure) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            executor.submit(() -> queuedRan.set(true));
            ExecutorShutdown.close(executor);
            assertTrue(executor.isTerminated());
            assertTrue(interrupted.get());
            assertFalse(queuedRan.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restoresTheCallingThreadsInterruptFlagAfterCompletingCleanup() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            ExecutorShutdown.close(executor);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(executor.isTerminated());
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
        }
    }
}
