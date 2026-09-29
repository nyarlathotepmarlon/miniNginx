package com.example.proxy.balance;

import static com.example.proxy.balance.LoadBalancerTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendRegistry;
import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import com.example.proxy.config.BackendConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(15)
class LoadBalancerTest {
    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void filtersUnhealthyUnknownAndExcludedBackends(LoadBalancingStrategy strategy) {
        LoadBalancer balancer = LoadBalancer.create(strategy);
        BackendSnapshot a = backend("a", 3);
        BackendSnapshot b = backend("b", 1);
        BackendSnapshot unknown = new BackendSnapshot("unknown", a.baseUri(), 100,
                "/health", BackendState.UNKNOWN, 0, 0);
        BackendSnapshot unhealthy = new BackendSnapshot("unhealthy", a.baseUri(), 100,
                "/health", BackendState.UNHEALTHY, 0, 2);
        assertSame(b, balancer.select(List.of(unknown, a, unhealthy, b), Set.of("a")).orElseThrow());
        assertTrue(balancer.select(List.of(unknown, unhealthy), Set.of()).isEmpty());
        assertTrue(balancer.select(List.of(), Set.of()).isEmpty());
        assertTrue(balancer.select(List.of(a, b), Set.of("a", "b")).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void handlesSingleBackendAndRequestLocalExclusion(LoadBalancingStrategy strategy) {
        LoadBalancer balancer = LoadBalancer.create(strategy);
        List<BackendSnapshot> candidates = List.of(backend("a", 3));
        assertTrue(selections(balancer, candidates, 50).stream().allMatch("a"::equals));
        assertTrue(balancer.select(candidates, Set.of("a")).isEmpty());
        assertEquals("a", balancer.select(candidates, Set.of("unrelated")).orElseThrow().id());
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void neverRepeatsAnAlreadyAttemptedBackendOrMutatesInputs(LoadBalancingStrategy strategy) {
        LoadBalancer balancer = LoadBalancer.create(strategy);
        List<BackendSnapshot> candidates = List.of(backend("a", 3), backend("b", 1), backend("c", 2));
        Set<String> tried = new HashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            Set<String> exclusions = Set.copyOf(tried);
            BackendSnapshot selected = balancer.select(candidates, exclusions).orElseThrow();
            assertTrue(tried.add(selected.id()));
            assertEquals(i, exclusions.size());
        }
        assertTrue(balancer.select(candidates, tried).isEmpty());
        assertEquals(Set.of("a", "b", "c"), tried);
        assertEquals(List.of(3, 1, 2), candidates.stream().map(BackendSnapshot::weight).toList());
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void givesEachFactoryInstanceIndependentState(LoadBalancingStrategy strategy) {
        List<BackendSnapshot> candidates = List.of(backend("a", 1), backend("b", 1));
        LoadBalancer first = LoadBalancer.create(strategy);
        LoadBalancer second = LoadBalancer.create(strategy);
        assertEquals("a", first.select(candidates, Set.of()).orElseThrow().id());
        assertEquals("a", second.select(candidates, Set.of()).orElseThrow().id());
        assertEquals("b", first.select(candidates, Set.of()).orElseThrow().id());
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void concurrentSelectionPreservesTheExactStablePoolDistribution(LoadBalancingStrategy strategy)
            throws Exception {
        LoadBalancer balancer = LoadBalancer.create(strategy);
        List<BackendSnapshot> candidates = List.of(backend("a", 3), backend("b", 1));
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                tasks.add(executor.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    for (int i = 0; i < 500; i++) {
                        BackendSnapshot selected = balancer.select(candidates, Set.of()).orElseThrow();
                        if (selected.id().equals("a")) {
                            aCount.incrementAndGet();
                        } else {
                            assertEquals("b", selected.id());
                            bCount.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
            assertEquals(strategy == LoadBalancingStrategy.ROUND_ROBIN ? 2000 : 3000, aCount.get());
            assertEquals(strategy == LoadBalancingStrategy.ROUND_ROBIN ? 2000 : 1000, bCount.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void concurrentRegistryChangesDoNotInvalidateAnInFlightSnapshot(LoadBalancingStrategy strategy)
            throws Exception {
        LoadBalancer balancer = LoadBalancer.create(strategy);
        List<BackendConfig> backends = List.of(backend("a", 3), backend("b", 1)).stream()
                .map(item -> new BackendConfig(item.id(), item.baseUri(), item.weight(), item.healthPath())).toList();
        BackendRegistry registry = new BackendRegistry(backends, 1, 1);
        registry.recordSuccess("a");
        registry.recordSuccess("b");
        var executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            tasks.add(executor.submit(() -> {
                assertTrue(start.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 500; i++) {
                    registry.recordFailure("a");
                    registry.recordSuccess("a");
                }
                return null;
            }));
            for (int reader = 0; reader < 4; reader++) {
                tasks.add(executor.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    for (int i = 0; i < 500; i++) {
                        List<BackendSnapshot> snapshot = registry.candidates();
                        BackendSnapshot selected = balancer.select(snapshot, Set.of()).orElseThrow();
                        assertEquals(BackendState.HEALTHY, selected.state());
                        assertTrue(snapshot.contains(selected));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
