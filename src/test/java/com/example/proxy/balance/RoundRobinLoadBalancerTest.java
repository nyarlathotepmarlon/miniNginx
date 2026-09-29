package com.example.proxy.balance;

import static com.example.proxy.balance.LoadBalancerTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendSnapshot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoundRobinLoadBalancerTest {
    private final List<BackendSnapshot> candidates = List.of(backend("a", 3), backend("b", 1), backend("c", 2));

    @Test
    void rotatesInConfigurationOrderWithoutApplyingWeights() {
        assertEquals(List.of("a", "b", "c", "a", "b", "c"),
                selections(new RoundRobinLoadBalancer(), candidates, 6));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE - 1, Integer.MIN_VALUE, -1})
    void keepsIndexesNonNegativeAcrossCounterOverflow(int initialSequence) {
        RoundRobinLoadBalancer balancer = new RoundRobinLoadBalancer(initialSequence);
        int expectedSequence = initialSequence;
        for (int i = 0; i < 6; i++) {
            assertSame(candidates.get(Math.floorMod(expectedSequence++, candidates.size())),
                    balancer.select(candidates, Set.of()).orElseThrow());
        }
    }

    @Test
    void emptySelectionsDoNotConsumeTickets() {
        RoundRobinLoadBalancer balancer = new RoundRobinLoadBalancer();
        assertEquals("a", balancer.select(candidates, Set.of()).orElseThrow().id());
        assertTrue(balancer.select(List.of(), Set.of()).isEmpty());
        assertTrue(balancer.select(candidates, Set.of("a", "b", "c")).isEmpty());
        assertEquals("b", balancer.select(candidates, Set.of()).orElseThrow().id());
    }

    @Test
    void appliesTheCurrentCounterToTheCurrentSnapshotAfterMembershipChanges() {
        RoundRobinLoadBalancer balancer = new RoundRobinLoadBalancer();
        assertEquals(List.of("a", "b", "c"), selections(balancer, candidates, 3));
        assertSame(candidates.get(2), balancer.select(List.of(candidates.get(0), candidates.get(2)), Set.of())
                .orElseThrow());
        assertSame(candidates.get(1), balancer.select(candidates, Set.of()).orElseThrow());
    }
}
