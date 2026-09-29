package com.example.proxy.proxy;

import com.example.proxy.backend.BackendPool;
import com.example.proxy.backend.BackendSnapshot;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stable forwarding pipeline. Stage 2 performs exactly one transport call per business request. */
public final class RequestForwarder implements HttpHandler, AutoCloseable {
    private final ProxyConfig config;
    private final BackendPool pool;
    private final LoadBalancer selector;
    private final UpstreamTransport transport;
    private final AccessLogger accessLogger;
    private final Set<ResponseWriter> activeWriters = ConcurrentHashMap.newKeySet();
    private final Set<UpstreamResponse> activeResponses = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RequestForwarder(ProxyConfig config, BackendPool pool, LoadBalancer selector,
            UpstreamTransport transport, AccessLogger accessLogger) {
        this.config = java.util.Objects.requireNonNull(config);
        this.pool = java.util.Objects.requireNonNull(pool);
        this.selector = java.util.Objects.requireNonNull(selector);
        this.transport = java.util.Objects.requireNonNull(transport);
        this.accessLogger = java.util.Objects.requireNonNull(accessLogger);
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
            path = TargetUri.rawPath(exchange.getRequestURI());
            String managementPrefix = config.managementPathPrefix();
            if (path.equals(managementPrefix) || path.startsWith(managementPrefix + "/")) {
                if (!path.equals(managementPrefix + "/health")) {
                    error = "NOT_FOUND";
                    writer.json(404, errorJson(error));
                } else if (!method.equals("GET")) {
                    error = "METHOD_NOT_ALLOWED";
                    writer.json(405, errorJson(error));
                } else {
                    int eligible = pool.candidates().size();
                    writer.json(eligible > 0 ? 200 : 503,
                            "{\"status\":\"" + (eligible > 0 ? "UP" : "DOWN")
                                    + "\",\"mode\":\"single-backend\",\"healthChecksEnabled\":false"
                                    + ",\"totalBackends\":" + config.backends().size()
                                    + ",\"eligibleBackends\":" + eligible + "}");
                }
                return;
            }
            List<BackendSnapshot> candidates = List.copyOf(pool.candidates());
            var selected = selector.select(candidates, Set.of());
            if (selected.isEmpty()) {
                error = "NO_BACKEND";
                writer.json(503, errorJson(error));
                return;
            }
            BackendSnapshot backend = selected.orElseThrow();
            backendId = backend.id();
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
            URI target = TargetUri.resolve(backend.baseUri(), exchange.getRequestURI());
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
                    .timeout(config.requestTimeout())
                    .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            HeaderFilter.copyRequestHeaders(exchange.getRequestHeaders(), request, clientAddress, requestId);
            attempts = 1;
            try (UpstreamResponse response = transport.send(request.build())) {
                activeResponses.add(response);
                try {
                    if (closed.get()) {
                        throw new IOException("Proxy is shutting down");
                    }
                    writer.forward(response);
                } finally {
                    activeResponses.remove(response);
                }
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
        } catch (IllegalArgumentException failure) {
            error = "INVALID_REQUEST";
            fail(writer, 400, error);
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
