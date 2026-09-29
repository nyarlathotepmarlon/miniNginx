package com.example.proxy.proxy;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    @Test
    void countsOnlyDistinctHealthyIdentitiesAndNeverIncludesUnknownOrUnhealthyInstances() {
        RetryPolicy policy = new RetryPolicy(retryConfig(config(URI.create("http://localhost")), 10, Set.of()));
        BackendSnapshot healthy = backend("a", BackendState.HEALTHY);
        List<BackendSnapshot> candidates = List.of(healthy, healthy, backend("b", BackendState.HEALTHY),
                backend("c", BackendState.UNKNOWN), backend("d", BackendState.UNHEALTHY));
        assertEquals(2, policy.attemptLimit("GET", candidates));
        assertEquals(1, policy.attemptLimit("POST", candidates));
        assertEquals(0, policy.attemptLimit("GET", List.of()));
        assertEquals(0, policy.attemptLimit("PATCH", List.of(backend("c", BackendState.UNKNOWN))));
    }

    @Test
    void maxAttemptsIncludesTheFirstAttempt() {
        RetryPolicy policy = new RetryPolicy(retryConfig(config(URI.create("http://localhost")), 1, Set.of(503)));
        assertEquals(1, policy.attemptLimit("PUT", List.of(backend("a", BackendState.HEALTHY),
                backend("b", BackendState.HEALTHY))));
        assertTrue(policy.retriesStatus(503));
        assertFalse(policy.retriesStatus(502));
    }

    private static BackendSnapshot backend(String id, BackendState state) {
        return new BackendSnapshot(id, URI.create("http://localhost/" + id), 1, "/health", state, 0, 0);
    }
}
