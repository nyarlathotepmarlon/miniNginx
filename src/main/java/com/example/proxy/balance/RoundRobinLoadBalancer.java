package com.example.proxy.balance;

import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** One atomic ticket per successful selection, in the supplied candidate order. */
public final class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicInteger sequence;

    public RoundRobinLoadBalancer() {
        this(0);
    }

    // Allows deterministic overflow testing without billions of selections.
    RoundRobinLoadBalancer(int initialSequence) {
        sequence = new AtomicInteger(initialSequence);
    }

    @Override
    public Optional<BackendSnapshot> select(
            List<BackendSnapshot> candidates, Set<String> excludedBackendIds) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(excludedBackendIds, "excludedBackendIds must not be null");
        List<BackendSnapshot> eligible = candidates.stream()
                .filter(backend -> backend.state() == BackendState.HEALTHY)
                .filter(backend -> !excludedBackendIds.contains(backend.id()))
                .toList();
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(eligible.get(Math.floorMod(sequence.getAndIncrement(), eligible.size())));
    }
}
