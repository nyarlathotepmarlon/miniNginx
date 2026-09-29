package com.example.proxy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.proxy.config.BackendConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BackendRegistryTest {
    @Test
    void startsUnknownAndExcludesInstancesUntilTheSuccessThresholdIsReached() {
        BackendRegistry registry = new BackendRegistry(List.of(backend("a"), backend("b")), 2, 2);

        assertEquals(2, registry.size());
        assertEquals(0, registry.healthyCount());
        assertTrue(registry.candidates().isEmpty());
        assertEquals(BackendState.UNKNOWN, registry.backend("a").orElseThrow().state());

        BackendStatusUpdate first = registry.recordSuccess("a");
        assertFalse(first.stateChanged());
        assertEquals(1, first.current().consecutiveSuccesses());
        assertEquals(BackendState.UNKNOWN, first.current().state());

        BackendStatusUpdate second = registry.recordSuccess("a");
        assertTrue(second.stateChanged());
        assertEquals(BackendState.HEALTHY, second.current().state());
        assertEquals(List.of("a"), registry.candidates().stream().map(BackendSnapshot::id).toList());
    }

    @Test
    void removesAndRestoresAHealthyBackendAtTheConfiguredThresholds() {
        BackendRegistry registry = new BackendRegistry(List.of(backend("a")), 2, 2);
        registry.recordSuccess("a");
        registry.recordSuccess("a");

        BackendStatusUpdate firstFailure = registry.recordFailure("a");
        assertFalse(firstFailure.stateChanged());
        assertEquals(BackendState.HEALTHY, firstFailure.current().state());
        assertEquals(1, firstFailure.current().consecutiveFailures());
        assertEquals(0, firstFailure.current().consecutiveSuccesses());

        BackendStatusUpdate secondFailure = registry.recordFailure("a");
        assertTrue(secondFailure.stateChanged());
        assertEquals(BackendState.UNHEALTHY, secondFailure.current().state());
        assertTrue(registry.candidates().isEmpty());

        BackendStatusUpdate firstRecovery = registry.recordSuccess("a");
        assertFalse(firstRecovery.stateChanged());
        assertEquals(BackendState.UNHEALTHY, firstRecovery.current().state());
        assertEquals(0, firstRecovery.current().consecutiveFailures());

        BackendStatusUpdate secondRecovery = registry.recordSuccess("a");
        assertTrue(secondRecovery.stateChanged());
        assertEquals(BackendState.HEALTHY, secondRecovery.current().state());
        assertEquals(1, registry.healthyCount());
    }

    @Test
    void movesUnknownBackendToUnhealthyAfterConsecutiveFailures() {
        BackendRegistry registry = new BackendRegistry(List.of(backend("a")), 2, 1);

        assertEquals(BackendState.UNKNOWN, registry.recordFailure("a").current().state());
        assertEquals(BackendState.UNHEALTHY, registry.recordFailure("a").current().state());
    }

    @Test
    void resetsTheOppositeCounterWhenProbeOutcomeChanges() {
        BackendRegistry registry = new BackendRegistry(List.of(backend("a")), 3, 3);

        registry.recordSuccess("a");
        BackendSnapshot afterFailure = registry.recordFailure("a").current();
        assertEquals(0, afterFailure.consecutiveSuccesses());
        assertEquals(1, afterFailure.consecutiveFailures());

        BackendSnapshot afterSuccess = registry.recordSuccess("a").current();
        assertEquals(1, afterSuccess.consecutiveSuccesses());
        assertEquals(0, afterSuccess.consecutiveFailures());
    }

    @Test
    void returnsImmutableOrderedSnapshots() {
        BackendRegistry registry = new BackendRegistry(List.of(backend("a"), backend("b")), 1, 1);
        registry.recordSuccess("a");
        registry.recordSuccess("b");

        List<BackendSnapshot> all = registry.allBackends();
        List<BackendSnapshot> candidates = registry.candidates();

        assertEquals(List.of("a", "b"), all.stream().map(BackendSnapshot::id).toList());
        assertEquals(List.of("a", "b"), candidates.stream().map(BackendSnapshot::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
        assertThrows(UnsupportedOperationException.class, () -> candidates.clear());
    }

    @Test
    void rejectsUnknownIdsDuplicateIdsAndInvalidThresholds() {
        BackendRegistry registry = new BackendRegistry(List.of(backend("a")), 1, 1);

        assertThrows(IllegalArgumentException.class, () -> registry.recordSuccess("missing"));
        assertThrows(IllegalArgumentException.class, () -> registry.recordFailure("missing"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendRegistry(List.of(backend("a"), backend("a")), 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendRegistry(List.of(backend("a")), 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackendRegistry(List.of(backend("a")), 1, 0));
    }

    @Test
    void supportsConcurrentStateUpdatesAndSnapshotReads() throws Exception {
        BackendRegistry registry = new BackendRegistry(List.of(backend("a")), 1, 1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                tasks.add(() -> {
                    registry.recordSuccess("a");
                    registry.allBackends();
                    registry.candidates();
                    return null;
                });
            }
            executor.invokeAll(tasks).forEach(future -> {
                try {
                    future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(BackendState.HEALTHY, registry.backend("a").orElseThrow().state());
        assertEquals(1, registry.healthyCount());
    }

    private static BackendConfig backend(String id) {
        int port = "a".equals(id) ? 9001 : 9002;
        return new BackendConfig(
                id, URI.create("http://127.0.0.1:" + port), 1, "/health");
    }
}
