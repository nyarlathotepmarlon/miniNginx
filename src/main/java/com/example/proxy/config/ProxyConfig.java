package com.example.proxy.config;

import com.example.proxy.balance.LoadBalancingStrategy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ProxyConfig(
        InetSocketAddress listenAddress,
        int workerThreads,
        LoadBalancingStrategy loadBalancingStrategy,
        List<BackendConfig> backends,
        Duration healthInterval,
        Duration healthTimeout,
        int healthFailureThreshold,
        int healthSuccessThreshold,
        Duration connectTimeout,
        Duration requestTimeout,
        long maxRequestBodyBytes,
        int maxAttempts,
        Set<Integer> retryStatusCodes,
        String managementPathPrefix) {

    public ProxyConfig {
        Objects.requireNonNull(listenAddress, "listenAddress must not be null");
        Objects.requireNonNull(loadBalancingStrategy, "loadBalancingStrategy must not be null");
        Objects.requireNonNull(backends, "backends must not be null");
        Objects.requireNonNull(healthInterval, "healthInterval must not be null");
        Objects.requireNonNull(healthTimeout, "healthTimeout must not be null");
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        Objects.requireNonNull(retryStatusCodes, "retryStatusCodes must not be null");
        Objects.requireNonNull(managementPathPrefix, "managementPathPrefix must not be null");

        // Port 0 is intentionally permitted for programmatic construction in integration tests.
        // ConfigLoader applies the stricter production range of 1..65535.
        if (listenAddress.getPort() < 0 || listenAddress.getPort() > 65_535) {
            throw new IllegalArgumentException("listen port must be between 0 and 65535");
        }
        if (workerThreads <= 0) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("at least one backend must be configured");
        }

        List<BackendConfig> backendCopy = List.copyOf(backends);
        Set<String> backendIds = new HashSet<>();
        for (BackendConfig backend : backendCopy) {
            Objects.requireNonNull(backend, "backends must not contain null");
            if (!backendIds.add(backend.id())) {
                throw new IllegalArgumentException("duplicate backend id: " + backend.id());
            }
        }

        requirePositive(healthInterval, "healthInterval");
        requirePositive(healthTimeout, "healthTimeout");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        if (healthFailureThreshold <= 0) {
            throw new IllegalArgumentException("healthFailureThreshold must be positive");
        }
        if (healthSuccessThreshold <= 0) {
            throw new IllegalArgumentException("healthSuccessThreshold must be positive");
        }
        if (maxRequestBodyBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }

        LinkedHashSet<Integer> statusCodeCopy = new LinkedHashSet<>();
        for (Integer statusCode : retryStatusCodes) {
            if (statusCode == null || statusCode < 400 || statusCode > 599) {
                throw new IllegalArgumentException(
                        "retry status codes must be between 400 and 599: " + statusCode);
            }
            statusCodeCopy.add(statusCode);
        }
        validateManagementPathPrefix(managementPathPrefix);

        backends = backendCopy;
        retryStatusCodes = Collections.unmodifiableSet(statusCodeCopy);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void validateManagementPathPrefix(String prefix) {
        if (prefix.isBlank()
                || !prefix.startsWith("/")
                || "/".equals(prefix)
                || prefix.endsWith("/")) {
            throw new IllegalArgumentException(
                    "managementPathPrefix must start with /, must not be /, and must not end with /");
        }
        final URI uri;
        try {
            uri = URI.create(prefix);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("managementPathPrefix is not a valid URI path", exception);
        }
        if (uri.isAbsolute()
                || uri.getRawAuthority() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !prefix.equals(uri.getRawPath())) {
            throw new IllegalArgumentException(
                    "managementPathPrefix must not contain an authority, query, or fragment");
        }
    }
}
