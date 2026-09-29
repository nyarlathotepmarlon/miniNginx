package com.example.proxy.config;

import com.example.proxy.balance.LoadBalancingStrategy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public final class ConfigLoader {
    static final String DEFAULT_LISTEN_HOST = "127.0.0.1";
    static final int DEFAULT_LISTEN_PORT = 8080;
    static final int DEFAULT_BACKEND_WEIGHT = 1;
    static final String DEFAULT_HEALTH_PATH = "/health";
    static final long DEFAULT_HEALTH_INTERVAL_MILLIS = 5_000;
    static final long DEFAULT_HEALTH_TIMEOUT_MILLIS = 1_000;
    static final int DEFAULT_HEALTH_FAILURE_THRESHOLD = 2;
    static final int DEFAULT_HEALTH_SUCCESS_THRESHOLD = 1;
    static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 1_000;
    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 3_000;
    static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 10L * 1024 * 1024;
    static final int DEFAULT_MAX_ATTEMPTS = 2;
    static final String DEFAULT_RETRY_STATUS_CODES = "502,503,504";
    static final String DEFAULT_MANAGEMENT_PATH_PREFIX = "/_proxy";

    private ConfigLoader() {
    }

    public static ProxyConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        if (!Files.isRegularFile(path)) {
            throw new IOException("文件不存在或不是普通文件");
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return fromProperties(properties);
    }

    public static ProxyConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties must not be null");

        String listenHost = value(properties, "listen.host", DEFAULT_LISTEN_HOST);
        int listenPort = positiveInt(properties, "listen.port", DEFAULT_LISTEN_PORT);
        if (listenPort > 65_535) {
            throw invalid("listen.port", "must be between 1 and 65535");
        }

        int defaultWorkerThreads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        int workerThreads = positiveInt(properties, "proxy.worker-threads", defaultWorkerThreads);
        LoadBalancingStrategy strategy = LoadBalancingStrategy.fromConfigValue(
                value(properties, "load-balancer.strategy", "round-robin"));
        List<BackendConfig> backends = parseBackends(properties);

        Duration healthInterval = positiveDuration(
                properties, "health.interval-ms", DEFAULT_HEALTH_INTERVAL_MILLIS);
        Duration healthTimeout = positiveDuration(
                properties, "health.timeout-ms", DEFAULT_HEALTH_TIMEOUT_MILLIS);
        int failureThreshold = positiveInt(
                properties, "health.failure-threshold", DEFAULT_HEALTH_FAILURE_THRESHOLD);
        int successThreshold = positiveInt(
                properties, "health.success-threshold", DEFAULT_HEALTH_SUCCESS_THRESHOLD);
        Duration connectTimeout = positiveDuration(
                properties, "proxy.connect-timeout-ms", DEFAULT_CONNECT_TIMEOUT_MILLIS);
        Duration requestTimeout = positiveDuration(
                properties, "proxy.request-timeout-ms", DEFAULT_REQUEST_TIMEOUT_MILLIS);
        long maxRequestBodyBytes = positiveLong(
                properties, "proxy.max-request-body-bytes", DEFAULT_MAX_REQUEST_BODY_BYTES);
        int maxAttempts = positiveInt(properties, "retry.max-attempts", DEFAULT_MAX_ATTEMPTS);
        Set<Integer> retryStatusCodes = parseStatusCodes(properties.getProperty("retry.status-codes"));
        String managementPathPrefix = value(
                properties, "management.path-prefix", DEFAULT_MANAGEMENT_PATH_PREFIX);

        return new ProxyConfig(
                new InetSocketAddress(listenHost, listenPort),
                workerThreads,
                strategy,
                backends,
                healthInterval,
                healthTimeout,
                failureThreshold,
                successThreshold,
                connectTimeout,
                requestTimeout,
                maxRequestBodyBytes,
                maxAttempts,
                retryStatusCodes,
                managementPathPrefix);
    }

    private static List<BackendConfig> parseBackends(Properties properties) {
        String backendList = required(properties, "backends");
        String[] rawIds = backendList.split(",", -1);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            String id = rawId.strip();
            if (id.isEmpty()) {
                throw invalid("backends", "must not contain an empty backend id");
            }
            if (!ids.add(id)) {
                throw invalid("backends", "contains duplicate backend id: " + id);
            }
        }

        List<BackendConfig> backends = new ArrayList<>(ids.size());
        for (String id : ids) {
            String prefix = "backend." + id + ".";
            URI baseUri;
            try {
                baseUri = URI.create(required(properties, prefix + "url"));
            } catch (IllegalArgumentException exception) {
                throw invalid(prefix + "url", exception.getMessage(), exception);
            }
            int weight = positiveInt(properties, prefix + "weight", DEFAULT_BACKEND_WEIGHT);
            String healthPath = value(properties, prefix + "health-path", DEFAULT_HEALTH_PATH);
            backends.add(new BackendConfig(id, baseUri, weight, healthPath));
        }
        return List.copyOf(backends);
    }

    private static Set<Integer> parseStatusCodes(String configuredValue) {
        String value = configuredValue == null ? DEFAULT_RETRY_STATUS_CODES : configuredValue.strip();
        if (value.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<Integer> statusCodes = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            String normalized = token.strip();
            if (normalized.isEmpty()) {
                throw invalid("retry.status-codes", "must not contain an empty status code");
            }
            final int statusCode;
            try {
                statusCode = Integer.parseInt(normalized);
            } catch (NumberFormatException exception) {
                throw invalid("retry.status-codes", "contains a non-integer value: " + normalized, exception);
            }
            if (statusCode < 400 || statusCode > 599) {
                throw invalid("retry.status-codes", "status codes must be between 400 and 599");
            }
            statusCodes.add(statusCode);
        }
        return Set.copyOf(statusCodes);
    }

    private static Duration positiveDuration(Properties properties, String key, long defaultMillis) {
        return Duration.ofMillis(positiveLong(properties, key, defaultMillis));
    }

    private static int positiveInt(Properties properties, String key, int defaultValue) {
        String rawValue = value(properties, key, Integer.toString(defaultValue));
        final int parsed;
        try {
            parsed = Integer.parseInt(rawValue);
        } catch (NumberFormatException exception) {
            throw invalid(key, "must be an integer", exception);
        }
        if (parsed <= 0) {
            throw invalid(key, "must be positive");
        }
        return parsed;
    }

    private static long positiveLong(Properties properties, String key, long defaultValue) {
        String rawValue = value(properties, key, Long.toString(defaultValue));
        final long parsed;
        try {
            parsed = Long.parseLong(rawValue);
        } catch (NumberFormatException exception) {
            throw invalid(key, "must be a long integer", exception);
        }
        if (parsed <= 0) {
            throw invalid(key, "must be positive");
        }
        return parsed;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw invalid(key, "is required and must not be blank");
        }
        return value.strip();
    }

    private static String value(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key, defaultValue);
        if (value == null || value.isBlank()) {
            throw invalid(key, "must not be blank");
        }
        return value.strip();
    }

    private static IllegalArgumentException invalid(String key, String detail) {
        return new IllegalArgumentException(key + " " + detail);
    }

    private static IllegalArgumentException invalid(String key, String detail, Throwable cause) {
        return new IllegalArgumentException(key + " " + detail, cause);
    }
}
