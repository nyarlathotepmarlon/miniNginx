package com.example.proxy.balance;

import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Smooth weighted round-robin; the monitor protects topology and all accumulated weights. */
public final class SmoothWeightedRoundRobinLoadBalancer implements LoadBalancer {
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized Optional<BackendSnapshot> select(
            List<BackendSnapshot> candidates, Set<String> excludedBackendIds) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(excludedBackendIds, "excludedBackendIds must not be null");
        Map<String, BackendSnapshot> healthy = new LinkedHashMap<>();
        for (BackendSnapshot backend : candidates) {
            if (backend.state() == BackendState.HEALTHY
                    && healthy.putIfAbsent(backend.id(), backend) != null) {
                throw new IllegalArgumentException("duplicate backend id: " + backend.id());
            }
        }
        if (topologyChanged(healthy)) {
            entries.clear();
            healthy.forEach((id, backend) -> entries.put(id, new Entry(backend.baseUri(), backend.weight())));
        }

        Entry best = null;
        String selectedId = null;
        long totalWeight = 0;
        for (var candidate : entries.entrySet()) {
            if (excludedBackendIds.contains(candidate.getKey())) {
                // A request-local exclusion is not a health change and must not reset the scheduler.
                continue;
            }
            Entry entry = candidate.getValue();
            entry.currentWeight += entry.weight;
            totalWeight += entry.weight;
            // Strict > keeps the initial topology's order as the deterministic tie-breaker.
            if (best == null || entry.currentWeight > best.currentWeight) {
                best = entry;
                selectedId = candidate.getKey();
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        best.currentWeight -= totalWeight;
        // Return the caller's current snapshot, not an older snapshot cached with algorithm state.
        return Optional.of(healthy.get(selectedId));
    }

    private boolean topologyChanged(Map<String, BackendSnapshot> healthy) {
        if (healthy.size() != entries.size()) {
            return true;
        }
        for (var candidate : healthy.entrySet()) {
            Entry entry = entries.get(candidate.getKey());
            BackendSnapshot backend = candidate.getValue();
            if (entry == null || entry.weight != backend.weight() || !entry.uri.equals(backend.baseUri())) {
                return true;
            }
        }
        // Object identity, probe counters, health paths and list-only reordering do not affect weights.
        return false;
    }

    private static final class Entry {
        private final URI uri;
        private final int weight;
        private long currentWeight;

        private Entry(URI uri, int weight) {
            this.uri = uri;
            this.weight = weight;
        }
    }
}
