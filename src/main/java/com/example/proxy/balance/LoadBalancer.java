package com.example.proxy.balance;

import com.example.proxy.backend.BackendSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Selects a HEALTHY backend from an immutable request snapshot, excluding already attempted IDs.
 * Implementations do not mutate the caller's snapshot or exclusion set.
 */
@FunctionalInterface
public interface LoadBalancer {
    Optional<BackendSnapshot> select(
            List<BackendSnapshot> candidates, Set<String> excludedBackendIds);

    /** Each proxy owns a separate scheduler; no mutable algorithm state is shared between servers. */
    static LoadBalancer create(LoadBalancingStrategy strategy) {
        return switch (Objects.requireNonNull(strategy, "strategy must not be null")) {
            case ROUND_ROBIN -> new RoundRobinLoadBalancer();
            case WEIGHTED_ROUND_ROBIN -> new SmoothWeightedRoundRobinLoadBalancer();
        };
    }
}
