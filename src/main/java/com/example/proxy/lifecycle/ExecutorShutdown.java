package com.example.proxy.lifecycle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded, two-phase shutdown for the proxy's owned, interruptible executors. */
public final class ExecutorShutdown {
    private ExecutorShutdown() {
    }

    public static void close(ExecutorService executor) {
        boolean interrupted = false;
        executor.shutdown();
        try {
            try {
                if (executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException failure) {
                interrupted = true;
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Executor did not terminate after cancellation");
                }
            } catch (InterruptedException failure) {
                interrupted = true;
                executor.shutdownNow();
                throw new IllegalStateException("Interrupted while awaiting executor termination", failure);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
