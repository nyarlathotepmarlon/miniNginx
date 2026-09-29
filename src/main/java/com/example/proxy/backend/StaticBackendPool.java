package com.example.proxy.backend;

import com.example.proxy.config.BackendConfig;
import java.util.List;
import java.util.Objects;

/** Explicitly eligible backends for single-backend mode and deterministic tests; no probes run. */
public final class StaticBackendPool implements BackendPool {
    private final List<BackendSnapshot> candidates;

    public StaticBackendPool(List<BackendConfig> backends) {
        Objects.requireNonNull(backends, "backends must not be null");
        candidates = backends.stream()
                .map(backend -> BackendSnapshot.from(backend, BackendState.HEALTHY, 0, 0))
                .toList();
    }

    @Override
    public List<BackendSnapshot> candidates() {
        return candidates;
    }
}
