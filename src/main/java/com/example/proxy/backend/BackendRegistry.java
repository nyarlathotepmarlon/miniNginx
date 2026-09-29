package com.example.proxy.backend;

import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe owner of runtime health state for all configured backends.
 *
 * <p>Only HEALTHY instances are exposed through {@link #candidates()}. New instances start in
 * UNKNOWN and therefore receive no traffic until enough successful probes are recorded.
 */
public final class BackendRegistry implements BackendPool {
    private final Map<String, MutableBackendStatus> statuses;
    private final int failureThreshold;
    private final int successThreshold;

    public BackendRegistry(ProxyConfig config) {
        this(
                Objects.requireNonNull(config, "config must not be null").backends(),
                config.healthFailureThreshold(),
                config.healthSuccessThreshold());
    }

    public BackendRegistry(
            List<BackendConfig> backends, int failureThreshold, int successThreshold) {
        Objects.requireNonNull(backends, "backends must not be null");
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("at least one backend must be registered");
        }
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (successThreshold <= 0) {
            throw new IllegalArgumentException("successThreshold must be positive");
        }

        LinkedHashMap<String, MutableBackendStatus> statusMap = new LinkedHashMap<>();
        for (BackendConfig backend : backends) {
            Objects.requireNonNull(backend, "backends must not contain null");
            if (statusMap.putIfAbsent(backend.id(), new MutableBackendStatus(backend)) != null) {
                throw new IllegalArgumentException("duplicate backend id: " + backend.id());
            }
        }
        this.statuses = statusMap;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
    }

    public synchronized BackendStatusUpdate recordSuccess(String backendId) {
        MutableBackendStatus status = requireStatus(backendId);
        BackendSnapshot previous = status.snapshot();
        status.consecutiveFailures = 0;
        status.consecutiveSuccesses = incrementCapped(
                status.consecutiveSuccesses, successThreshold);
        if (status.state != BackendState.HEALTHY
                && status.consecutiveSuccesses >= successThreshold) {
            status.state = BackendState.HEALTHY;
        }
        return new BackendStatusUpdate(previous, status.snapshot());
    }

    public synchronized BackendStatusUpdate recordFailure(String backendId) {
        MutableBackendStatus status = requireStatus(backendId);
        BackendSnapshot previous = status.snapshot();
        status.consecutiveSuccesses = 0;
        status.consecutiveFailures = incrementCapped(
                status.consecutiveFailures, failureThreshold);
        if (status.state != BackendState.UNHEALTHY
                && status.consecutiveFailures >= failureThreshold) {
            status.state = BackendState.UNHEALTHY;
        }
        return new BackendStatusUpdate(previous, status.snapshot());
    }

    public synchronized Optional<BackendSnapshot> backend(String backendId) {
        Objects.requireNonNull(backendId, "backendId must not be null");
        MutableBackendStatus status = statuses.get(backendId);
        return status == null ? Optional.empty() : Optional.of(status.snapshot());
    }

    public synchronized List<BackendSnapshot> allBackends() {
        List<BackendSnapshot> snapshots = new ArrayList<>(statuses.size());
        for (MutableBackendStatus status : statuses.values()) {
            snapshots.add(status.snapshot());
        }
        return List.copyOf(snapshots);
    }

    @Override
    public synchronized List<BackendSnapshot> candidates() {
        List<BackendSnapshot> healthy = new ArrayList<>();
        for (MutableBackendStatus status : statuses.values()) {
            if (status.state == BackendState.HEALTHY) {
                healthy.add(status.snapshot());
            }
        }
        return List.copyOf(healthy);
    }

    public synchronized int size() {
        return statuses.size();
    }

    public synchronized int healthyCount() {
        int count = 0;
        for (MutableBackendStatus status : statuses.values()) {
            if (status.state == BackendState.HEALTHY) {
                count++;
            }
        }
        return count;
    }

    private MutableBackendStatus requireStatus(String backendId) {
        Objects.requireNonNull(backendId, "backendId must not be null");
        MutableBackendStatus status = statuses.get(backendId);
        if (status == null) {
            throw new IllegalArgumentException("unknown backend id: " + backendId);
        }
        return status;
    }

    private static int incrementCapped(int current, int threshold) {
        return current >= threshold ? threshold : current + 1;
    }

    private static final class MutableBackendStatus {
        private final BackendConfig config;
        private BackendState state = BackendState.UNKNOWN;
        private int consecutiveSuccesses;
        private int consecutiveFailures;

        private MutableBackendStatus(BackendConfig config) {
            this.config = config;
        }

        private BackendSnapshot snapshot() {
            return BackendSnapshot.from(
                    config, state, consecutiveSuccesses, consecutiveFailures);
        }
    }
}
