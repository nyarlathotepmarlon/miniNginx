package com.example.proxy.proxy;

import com.example.proxy.backend.BackendPool;
import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import com.example.proxy.balance.LoadBalancer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.transport.UpstreamResponse;
import com.example.proxy.transport.UpstreamTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Buffers one request and retries distinct healthy backends only before downstream commitment. */
public final class RequestForwarder implements HttpHandler, AutoCloseable {
    private final ProxyConfig config;
    private final BackendPool pool;
    private final LoadBalancer selector;
    private final UpstreamTransport transport;
    private final AccessLogger accessLogger;
    private final RetryPolicy retryPolicy;
    private final boolean healthChecksEnabled;
    private final Set<ResponseWriter> activeWriters = ConcurrentHashMap.newKeySet();
    private final Set<UpstreamResponse> activeResponses = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RequestForwarder(ProxyConfig config, BackendPool pool, LoadBalancer selector,
            UpstreamTransport transport, AccessLogger accessLogger) {
        this(config, pool, selector, transport, accessLogger, false);
    }

    public RequestForwarder(ProxyConfig config, BackendPool pool, LoadBalancer selector,
            UpstreamTransport transport, AccessLogger accessLogger, boolean healthChecksEnabled) {
        this.config = java.util.Objects.requireNonNull(config);
        this.pool = java.util.Objects.requireNonNull(pool);
        this.selector = java.util.Objects.requireNonNull(selector);
        this.transport = java.util.Objects.requireNonNull(transport);
        this.accessLogger = java.util.Objects.requireNonNull(accessLogger);
        this.retryPolicy = new RetryPolicy(config);
        this.healthChecksEnabled = healthChecksEnabled;
    }

    @Override
    public void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String clientAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
        String backendId = "-";
        String path = "-";
        String error = "NONE";
        int attempts = 0;
        exchange.getRequestBody();
        ResponseWriter writer = new ResponseWriter(exchange, requestId);
        activeWriters.add(writer);
        try {
            if (closed.get()) {
                error = "SHUTTING_DOWN";
                writer.json(503, errorJson(error));
                return;
            }
            String method = exchange.getRequestMethod();
            if (method.equals("CONNECT") || exchange.getRequestHeaders().containsKey("Upgrade")) {
                error = "UNSUPPORTED_PROTOCOL";
                writer.json(501, errorJson(error));
                return;
            }
            try {
                path = TargetUri.rawPath(exchange.getRequestURI());
            } catch (IllegalArgumentException failure) {
                error = "INVALID_REQUEST";
                writer.json(400, errorJson(error));
                return;
            }
            String managementPrefix = config.managementPathPrefix();
            if (path.equals(managementPrefix) || path.startsWith(managementPrefix + "/")) {
                if (!path.equals(managementPrefix + "/health")) {
                    error = "NOT_FOUND";
                    writer.json(404, errorJson(error));
                } else if (!method.equals("GET")) {
                    error = "METHOD_NOT_ALLOWED";
                    writer.json(405, errorJson(error));
                } else {
                    long eligible = pool.candidates().stream()
                            .filter(backend -> backend.state() == BackendState.HEALTHY).count();
                    writer.json(eligible > 0 ? 200 : 503,
                            "{\"status\":\"" + (eligible > 0 ? "UP" : "DOWN")
                                    + "\",\"mode\":\"" + (healthChecksEnabled ? "active-health-checks" : "static-backends")
                                    + "\",\"healthChecksEnabled\":" + healthChecksEnabled
                                    + ",\"loadBalancingStrategy\":\"" + config.loadBalancingStrategy().configValue() + "\""
                                    + ",\"totalBackends\":" + config.backends().size()
                                    + (healthChecksEnabled ? ",\"healthyBackends\":" + eligible : "")
                                    + ",\"eligibleBackends\":" + eligible + "}");
                }
                return;
            }
            List<BackendSnapshot> candidates = List.copyOf(pool.candidates());
            int attemptLimit = retryPolicy.attemptLimit(method, candidates);
            Set<String> attempted = new HashSet<>();
            var selected = selector.select(candidates, Set.of());
            if (selected.isEmpty()) {
                error = "NO_BACKEND";
                writer.json(503, errorJson(error));
                return;
            }
            BackendSnapshot backend = selected.orElseThrow();
            byte[] body;
            try {
                body = RequestBodyBuffer.read(exchange, config.maxRequestBodyBytes());
            } catch (RequestBodyBuffer.BodyTooLargeException failure) {
                error = "REQUEST_BODY_TOO_LARGE";
                writer.json(413, errorJson(error));
                return;
            } catch (IOException failure) {
                error = "CLIENT_REQUEST_FAILURE";
                writer.json(400, errorJson(error));
                return;
            }
            while (true) {
                if (closed.get()) {
                    error = "SHUTTING_DOWN";
                    fail(writer, 503, error);
                    return;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Request interrupted before upstream attempt");
                }
                final HttpRequest request;
                try {
                    request = buildRequest(exchange, backend, body, clientAddress, requestId);
                } catch (IllegalArgumentException failure) {
                    error = "INVALID_REQUEST";
                    writer.json(400, errorJson(error));
                    return;
                }
                backendId = backend.id();
                attempted.add(backendId);
                attempts++;
                BackendSnapshot next = null;
                UpstreamResponse response = null;
                try {
                    response = transport.send(request);
                    activeResponses.add(response);
                    try (UpstreamResponse owned = response) {
                        if (closed.get()) {
                            throw new IOException("Proxy is shutting down");
                        }
                        if (owned.statusCode() < 200) {
                            throw new IOException("Unsupported informational or upgrade response");
                        }
                        if (canRetry(attempts, attemptLimit) && retryPolicy.retriesStatus(owned.statusCode())) {
                            next = selector.select(candidates, Set.copyOf(attempted)).orElse(null);
                        }
                        if (next == null) {
                            // A final legal response always wins, including a retryable 503/504.
                            error = owned.statusCode() >= 400 ? "UPSTREAM_HTTP_ERROR" : "NONE";
                            writer.forward(owned);
                            return;
                        }
                        // Close the abandoned body before the next send; never consume it to EOF.
                    }
                } catch (IOException failure) {
                    // Includes protocol failures, but never replays a partially delivered response.
                    if (writer.committed() || !canRetry(attempts, attemptLimit)) {
                        throw failure;
                    }
                    if (next == null) {
                        next = selector.select(candidates, Set.copyOf(attempted)).orElseThrow(() -> failure);
                    }
                } finally {
                    if (response != null) {
                        activeResponses.remove(response);
                    }
                }
                backend = next;
            }
        } catch (ResponseWriter.StreamFailure failure) {
            error = failure.category();
            writer.abort();
        } catch (HttpTimeoutException failure) {
            error = "UPSTREAM_TIMEOUT";
            fail(writer, 504, error);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error = "INTERRUPTED";
            writer.abort();
        } catch (IOException failure) {
            error = writer.committed() ? "UPSTREAM_STREAM_FAILURE" : "UPSTREAM_IO_FAILURE";
            fail(writer, 502, error);
        } catch (RuntimeException failure) {
            error = "INTERNAL_ERROR";
            fail(writer, 500, error);
        } finally {
            exchange.close();
            activeWriters.remove(writer);
            // Query strings can contain credentials; record only the raw path.
            accessLogger.log(new AccessLogger.Event(Instant.now(), requestId, clientAddress,
                    exchange.getRequestMethod(), path, backendId, attempts, writer.status(), writer.bytes(),
                    (System.nanoTime() - started) / 1_000_000, error));
        }
    }

    private boolean canRetry(int attempts, int attemptLimit) {
        return attempts < attemptLimit && !closed.get() && !Thread.currentThread().isInterrupted();
    }

    private HttpRequest buildRequest(HttpExchange exchange, BackendSnapshot backend, byte[] body,
            String clientAddress, String requestId) {
        URI target = TargetUri.resolve(backend.baseUri(), exchange.getRequestURI());
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(config.requestTimeout())
                .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        HeaderFilter.copyRequestHeaders(exchange.getRequestHeaders(), request, clientAddress, requestId);
        return request.build();
    }

    private static void fail(ResponseWriter writer, int status, String error) {
        if (writer.committed()) {
            writer.abort();
            return;
        }
        try {
            writer.json(status, errorJson(error));
        } catch (IOException failure) {
            writer.abort();
        }
    }

    private static String errorJson(String error) {
        return "{\"error\":\"" + error + "\"}";
    }

    @Override
    public void close() {
        closed.set(true);
        activeWriters.forEach(ResponseWriter::abort);
        activeResponses.forEach(response -> {
            try {
                response.close();
            } catch (IOException ignored) {
                // Continue closing the other active responses during shutdown.
            }
        });
    }
}
