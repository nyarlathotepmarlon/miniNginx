package com.example.proxy.health;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendRegistry;
import com.example.proxy.backend.BackendState;
import com.example.proxy.backend.BackendStatusUpdate;
import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class HealthCheckerTest {
    @Test
    void rejectsMismatchedRegistryBeforeSchedulingTasks() {
        ProxyConfig config = configuration(2, Duration.ofSeconds(1), 1, 1);
        BackendRegistry incomplete = new BackendRegistry(configuration(1, Duration.ofSeconds(1), 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HealthChecker(config, incomplete, backend -> true, update -> {}));
    }

    @Test
    void appliesThresholdsResetsCountersAndLogsOnlyTransitions() {
        ProxyConfig config = configuration(1, Duration.ofDays(1), 2, 2);
        BackendRegistry registry = new BackendRegistry(config);
        Queue<Boolean> outcomes = new ArrayDeque<>(List.of(
                true, false, true, true, false, true, false, false, false, true, true, true));
        List<BackendStatusUpdate> events = new ArrayList<>();
        try (HealthChecker checker = new HealthChecker(config, registry, backend -> outcomes.remove(), events::add)) {
            assertEquals(BackendState.UNKNOWN, registry.backend("node-0").orElseThrow().state());
            checker.checkOnce();
            assertEquals(0, registry.healthyCount());
            checker.checkOnce();
            assertEquals(0, registry.backend("node-0").orElseThrow().consecutiveSuccesses());
            checker.checkOnce();
            assertEquals(0, registry.healthyCount());
            checker.checkOnce();
            assertEquals(1, registry.healthyCount());
            checker.checkOnce();
            assertEquals(1, registry.healthyCount());
            checker.checkOnce();
            assertEquals(0, registry.backend("node-0").orElseThrow().consecutiveFailures());
            checker.checkOnce();
            checker.checkOnce();
            assertEquals(0, registry.healthyCount());
            checker.checkOnce();
            checker.checkOnce();
            assertEquals(0, registry.healthyCount());
            checker.checkOnce();
            checker.checkOnce();
            assertEquals(1, registry.healthyCount());
            assertEquals(List.of(BackendState.HEALTHY, BackendState.UNHEALTHY, BackendState.HEALTHY),
                    events.stream().map(event -> event.current().state()).toList());
            assertTrue(events.stream().allMatch(BackendStatusUpdate::stateChanged));
        }
    }

    @Test
    void ioTimeoutAndUnexpectedProbeExceptionsAreFailuresAndDoNotSkipOtherBackends() {
        ProxyConfig config = configuration(2, Duration.ofDays(1), 1, 1);
        BackendRegistry registry = new BackendRegistry(config);
        AtomicInteger aCalls = new AtomicInteger();
        BackendProbe probe = backend -> {
            if (backend.id().equals("node-1")) {
                return true;
            }
            return switch (aCalls.incrementAndGet()) {
                case 1 -> throw new IOException("connection failure");
                case 2 -> throw new HttpTimeoutException("probe timeout");
                case 3 -> throw new IllegalStateException("unexpected probe failure");
                default -> true;
            };
        };
        List<BackendStatusUpdate> events = new ArrayList<>();
        try (HealthChecker checker = new HealthChecker(config, registry, probe, events::add)) {
            for (int i = 0; i < 3; i++) {
                checker.checkOnce();
                assertEquals(BackendState.UNHEALTHY, registry.backend("node-0").orElseThrow().state());
                assertEquals(BackendState.HEALTHY, registry.backend("node-1").orElseThrow().state());
            }
            checker.checkOnce();
            assertEquals(2, registry.healthyCount());
            assertEquals(3, events.size());
        }
    }

    @Test
    void periodicTasksSurviveProbeAndLogSinkExceptions() throws Exception {
        ProxyConfig config = configuration(1, Duration.ofMillis(20), 1, 1);
        BackendRegistry registry = new BackendRegistry(config);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger logs = new AtomicInteger();
        try (HealthChecker checker = new HealthChecker(config, registry, backend -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first probe fails");
            }
            return true;
        }, update -> {
            logs.incrementAndGet();
            if (update.current().state() == BackendState.HEALTHY) {
                recovered.countDown();
            }
            throw new IllegalStateException("log sink fails");
        })) {
            checker.start();
            assertTrue(recovered.await(3, TimeUnit.SECONDS));
            assertEquals(1, registry.healthyCount());
            assertEquals(2, logs.get());
        }
    }

    @Test
    void immediatelySchedulesAllBackendsAtMostFourAtATimeAndStartIsIdempotent() throws Exception {
        ProxyConfig config = configuration(8, Duration.ofDays(1), 1, 1);
        BackendRegistry registry = new BackendRegistry(config);
        CountDownLatch firstFour = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allHealthy = new CountDownLatch(8);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        try (HealthChecker checker = new HealthChecker(config, registry, backend -> {
            threads.add(Thread.currentThread());
            calls.incrementAndGet();
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            firstFour.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return true;
            } finally {
                active.decrementAndGet();
            }
        }, update -> allHealthy.countDown())) {
            checker.start();
            checker.start();
            assertTrue(firstFour.await(3, TimeUnit.SECONDS));
            assertEquals(4, calls.get());
            release.countDown();
            assertTrue(allHealthy.await(3, TimeUnit.SECONDS));
            checker.close();
            checker.close();
            assertEquals(8, calls.get());
            assertEquals(4, maximum.get());
            assertEquals(4, threads.size());
            assertTrue(threads.stream().noneMatch(Thread::isDaemon));
            assertTrue(threads.stream().noneMatch(Thread::isAlive));
            assertThrows(IllegalStateException.class, checker::start);
        } finally {
            release.countDown();
        }
    }

    @Test
    void slowProbeDoesNotOverlapItselfOrBlockAnotherBackend() throws Exception {
        ProxyConfig config = configuration(2, Duration.ofMillis(10), 1, 1);
        BackendRegistry registry = new BackendRegistry(config);
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch otherRepeated = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger slowCalls = new AtomicInteger();
        try (HealthChecker checker = new HealthChecker(config, registry, backend -> {
            if (backend.id().equals("node-0")) {
                slowCalls.incrementAndGet();
                slowEntered.countDown();
                release.await();
            } else {
                otherRepeated.countDown();
            }
            return true;
        }, update -> {})) {
            checker.start();
            assertTrue(slowEntered.await(3, TimeUnit.SECONDS));
            assertTrue(otherRepeated.await(3, TimeUnit.SECONDS));
            assertEquals(1, slowCalls.get());
            // Close must interrupt the blocked probe rather than wait for its configured interval.
            checker.close();
        } finally {
            release.countDown();
        }
    }

    @Test
    void discardsLateResultsDuringCloseAndCanCloseBeforeStart() throws Exception {
        ProxyConfig config = configuration(1, Duration.ofDays(1), 1, 1);
        BackendRegistry registry = new BackendRegistry(config);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger logs = new AtomicInteger();
        try (HealthChecker checker = new HealthChecker(config, registry, backend -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException shutdown) {
                interrupted.countDown();
                return true; // Deliberately return a stale success after cancellation.
            }
            return false;
        }, update -> logs.incrementAndGet())) {
            checker.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            checker.close();
            assertEquals(0, interrupted.getCount());
            assertEquals(BackendState.UNKNOWN, registry.backend("node-0").orElseThrow().state());
            assertEquals(0, logs.get());
            checker.checkOnce();
            assertEquals(0, logs.get());
        }
        try (HealthChecker unused = new HealthChecker(config, registry, backend -> {
            throw new AssertionError("Closed-before-start checker must not probe");
        }, update -> {})) {
            unused.close();
            unused.close();
            unused.checkOnce();
            assertThrows(IllegalStateException.class, unused::start);
        }
    }

    private ProxyConfig configuration(int count, Duration interval, int failure, int success) {
        List<BackendConfig> backends = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            backends.add(new BackendConfig("node-" + i, URI.create("http://127.0.0.1:9001/" + i), 1, "/health"));
        }
        return healthConfig(config(backends, 1024, Duration.ofSeconds(1)), interval, Duration.ofSeconds(1), failure, success);
    }
}
