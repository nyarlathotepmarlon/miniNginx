package com.example.proxy.health;

import com.example.proxy.backend.BackendRegistry;
import com.example.proxy.backend.BackendStatusUpdate;
import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.lifecycle.ExecutorShutdown;
import com.example.proxy.logging.HealthLogger;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One periodic task per backend; a backend's probes never overlap. */
public final class HealthChecker implements AutoCloseable {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private final List<BackendConfig> backends;
    private final BackendRegistry registry;
    private final BackendProbe probe;
    private final HealthLogger logger;
    private final ScheduledThreadPoolExecutor scheduler;
    private final long intervalNanos;
    private final Object closeLock = new Object();
    private boolean started;
    private volatile boolean closed;

    public HealthChecker(ProxyConfig config, BackendRegistry registry, BackendProbe probe, HealthLogger logger) {
        backends = Objects.requireNonNull(config, "config must not be null").backends();
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        List<BackendConfig> registered = registry.allBackends().stream()
                .map(backend -> new BackendConfig(backend.id(), backend.baseUri(), backend.weight(), backend.healthPath()))
                .toList();
        if (!registered.equals(backends)) {
            throw new IllegalArgumentException("registry must match the configured backends");
        }
        intervalNanos = schedulingNanos(config.healthInterval());
        String prefix = "proxy-health-" + NEXT_ID.incrementAndGet() + "-";
        AtomicInteger nextThread = new AtomicInteger();
        scheduler = new ScheduledThreadPoolExecutor(Math.min(Math.max(1, backends.size()), 4), task -> {
            Thread thread = new Thread(task, prefix + nextThread.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Health checker is closed");
        }
        if (!started) {
            for (BackendConfig backend : backends) {
                scheduler.scheduleAtFixedRate(() -> checkOnce(backend), 0, intervalNanos, TimeUnit.NANOSECONDS);
            }
            started = true;
        }
    }

    /** Deterministic package-level seam; tests call this before starting any periodic tasks. */
    void checkOnce() {
        for (BackendConfig backend : backends) {
            checkOnce(backend);
        }
    }

    private void checkOnce(BackendConfig backend) {
        if (closed) {
            return;
        }
        boolean healthy;
        try {
            healthy = probe.check(backend);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            healthy = false;
        } catch (IOException | RuntimeException failure) {
            healthy = false;
        }
        BackendStatusUpdate update;
        synchronized (this) {
            // A late result or shutdown interrupt must not mark a stopped proxy's backend up/down.
            if (closed) {
                return;
            }
            update = healthy ? registry.recordSuccess(backend.id()) : registry.recordFailure(backend.id());
        }
        if (update.stateChanged()) {
            try {
                logger.log(update);
            } catch (RuntimeException ignored) {
                // A failing log sink must not cancel this backend's future scheduled checks.
            }
        }
    }

    @Override
    public void close() {
        synchronized (closeLock) {
            synchronized (this) {
                closed = true;
            }
            // Never hold the state-update monitor while waiting for active probes to complete.
            ExecutorShutdown.close(scheduler);
        }
    }

    private static long schedulingNanos(Duration interval) {
        try {
            return interval.toNanos();
        } catch (ArithmeticException beyondSchedulerRange) {
            return Long.MAX_VALUE;
        }
    }
}
