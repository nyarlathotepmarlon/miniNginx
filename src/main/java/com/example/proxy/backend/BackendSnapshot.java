package com.example.proxy.backend;

import com.example.proxy.config.BackendConfig;
import java.net.URI;
import java.util.Objects;

public record BackendSnapshot(
        String id,
        URI baseUri,
        int weight,
        String healthPath,
        BackendState state,
        int consecutiveSuccesses,
        int consecutiveFailures) {

    public BackendSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(baseUri, "baseUri must not be null");
        Objects.requireNonNull(healthPath, "healthPath must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        if (consecutiveSuccesses < 0 || consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutive counters must not be negative");
        }
    }

    static BackendSnapshot from(
            BackendConfig config,
            BackendState state,
            int consecutiveSuccesses,
            int consecutiveFailures) {
        return new BackendSnapshot(
                config.id(),
                config.baseUri(),
                config.weight(),
                config.healthPath(),
                state,
                consecutiveSuccesses,
                consecutiveFailures);
    }
}
