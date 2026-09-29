package com.example.proxy.proxy;

import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import com.example.proxy.config.ProxyConfig;
import java.util.List;
import java.util.Set;

/** Retry decisions made before response commitment; maxAttempts includes the initial send. */
final class RetryPolicy {
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");
    private final int maxAttempts;
    private final Set<Integer> statusCodes;

    RetryPolicy(ProxyConfig config) {
        maxAttempts = config.maxAttempts();
        statusCodes = config.retryStatusCodes();
    }

    int attemptLimit(String method, List<BackendSnapshot> candidates) {
        long healthyCount = candidates.stream()
                .filter(backend -> backend.state() == BackendState.HEALTHY)
                .map(BackendSnapshot::id).distinct().count();
        int methodLimit = IDEMPOTENT_METHODS.contains(method) ? maxAttempts : 1;
        return (int) Math.min(methodLimit, healthyCount);
    }

    boolean retriesStatus(int status) {
        return statusCodes.contains(status);
    }
}
