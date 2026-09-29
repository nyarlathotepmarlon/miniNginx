package com.example.proxy.balance;

import static com.example.proxy.balance.LoadBalancerTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SmoothWeightedRoundRobinLoadBalancerTest {
    private final LoadBalancer balancer = new SmoothWeightedRoundRobinLoadBalancer();
    private final List<BackendSnapshot> candidates = List.of(backend("a", 3), backend("b", 1));

    @Test
    void producesTheDeterministicThreeToOneSequence() {
        assertEquals(List.of("a", "a", "b", "a", "a", "a", "b", "a"),
                selections(balancer, candidates, 8));
    }

    @Test
    void matchesTheLongTermDistributionWithThreeDifferentWeights() {
        List<String> selected = selections(balancer,
                List.of(backend("a", 5), backend("b", 2), backend("c", 1)), 1200);
        assertEquals(750, Collections.frequency(selected, "a"));
        assertEquals(300, Collections.frequency(selected, "b"));
        assertEquals(150, Collections.frequency(selected, "c"));
    }

    @Test
    void retainsWeightsAcrossFreshSnapshotsProbeCountersAndReordering() {
        List<String> expected = List.of("a", "a", "b", "a", "a", "a", "b", "a");
        for (int i = 0; i < expected.size(); i++) {
            BackendSnapshot a = new BackendSnapshot("a", candidates.get(0).baseUri(), 3,
                    "/health-" + i, BackendState.HEALTHY, i, i % 2);
            BackendSnapshot b = new BackendSnapshot("b", candidates.get(1).baseUri(), 1,
                    "/health", BackendState.HEALTHY, i, 0);
            List<BackendSnapshot> fresh = i % 2 == 0 ? List.of(a, b) : List.of(b, a);
            assertSame(expected.get(i).equals("a") ? a : b, balancer.select(fresh, Set.of()).orElseThrow());
        }
    }

    @Test
    void exclusionsDoNotResetOrAccumulateCreditForExcludedBackends() {
        assertEquals("a", balancer.select(candidates, Set.of()).orElseThrow().id());
        assertTrue(balancer.select(candidates, Set.of("a", "b")).isEmpty());
        for (int i = 0; i < 10; i++) {
            assertEquals("b", balancer.select(candidates, Set.of("a")).orElseThrow().id());
        }
        assertEquals(List.of("a", "b", "a"), selections(balancer, candidates, 3));
    }

    @Test
    void rebuildsWhenABackendLeavesAndRejoinsIncludingAnEmptyPool() {
        assertEquals("a", balancer.select(candidates, Set.of()).orElseThrow().id());
        assertEquals(List.of("b", "b"), selections(balancer, List.of(candidates.get(1)), 2));
        assertEquals(List.of("a", "a", "b", "a"), selections(balancer, candidates, 4));
        assertEquals("a", balancer.select(candidates, Set.of()).orElseThrow().id());
        assertTrue(balancer.select(List.of(), Set.of()).isEmpty());
        assertEquals(List.of("a", "a", "b", "a"), selections(balancer, candidates, 4));
    }

    @Test
    void rebuildsWhenWeightsChange() {
        balancer.select(candidates, Set.of());
        assertEquals(List.of("b", "a", "b", "b"),
                selections(balancer, List.of(backend("a", 1), backend("b", 3)), 4));
    }

    @Test
    void rebuildsWhenAnIdPointsToANewEndpoint() {
        balancer.select(candidates, Set.of());
        BackendSnapshot replacement = new BackendSnapshot("a", URI.create("http://127.0.0.1:9002/new"),
                3, "/health", BackendState.HEALTHY, 1, 0);
        List<BackendSnapshot> updated = List.of(replacement, candidates.get(1));
        assertSame(replacement, balancer.select(updated, Set.of()).orElseThrow());
        assertEquals(List.of("a", "b", "a"), selections(balancer, updated, 3));
    }

    @Test
    void usesLongArithmeticWhenTotalWeightExceedsIntegerRange() {
        List<BackendSnapshot> large = List.of(backend("a", Integer.MAX_VALUE), backend("b", Integer.MAX_VALUE));
        assertEquals(List.of("a", "b", "a", "b", "a", "b"), selections(balancer, large, 6));
    }
}
