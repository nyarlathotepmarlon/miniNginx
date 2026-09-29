package com.example.proxy.proxy;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendPool;
import com.example.proxy.backend.BackendRegistry;
import com.example.proxy.backend.StaticBackendPool;
import com.example.proxy.balance.LoadBalancer;
import com.example.proxy.balance.LoadBalancingStrategy;
import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.support.MemoryExchange;
import com.example.proxy.transport.UpstreamResponse;
import com.example.proxy.transport.UpstreamTransport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(5)
class RequestForwarderRetryTest {
    private static final byte[] PAYLOAD = "正文-secret\u0000".getBytes(StandardCharsets.UTF_8);
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<AccessLogger.Event> logs = new ArrayList<>();

    @ParameterizedTest
    @MethodSource("retryableMethodsAndFailures")
    void retriesEligibleMethodsWithTheSameBodyMetadataAndPerAttemptTimeout(String method, String failure)
            throws Exception {
        ProxyConfig config = configuration(2);
        AtomicBoolean firstBodyClosed = new AtomicBoolean();
        MemoryExchange exchange = exchange(method);
        exchange.getRequestHeaders().set("Authorization", "Bearer secret-authorization");
        exchange.getRequestHeaders().set("Cookie", "secret-cookie");
        exchange.getRequestHeaders().set("X-Forwarded-For", "192.0.2.1");
        exchange.getRequestHeaders().set("Connection", "X-Private");
        exchange.getRequestHeaders().set("X-Private", "secret-hop");
        run(config, exchange, request -> requests.size() == 1
                ? outcome(failure, firstBodyClosed) : response(200, "成功"));

        assertEquals(200, exchange.status);
        assertEquals(List.of("/a/files/a%2Fb", "/b/files/a%2Fb"),
                requests.stream().map(request -> request.uri().getRawPath()).toList());
        for (HttpRequest request : requests) {
            assertEquals(method, request.method());
            assertEquals("token=secret-query&q=a%2Bb", request.uri().getRawQuery());
            assertEquals(config.requestTimeout(), request.timeout().orElseThrow());
            assertArrayEquals(PAYLOAD, bodyOf(request));
            assertEquals("Bearer secret-authorization", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("192.0.2.1, 127.0.0.1", request.headers().firstValue("X-Forwarded-For").orElseThrow());
            assertEquals("proxy.example", request.headers().firstValue("X-Forwarded-Host").orElseThrow());
            assertTrue(request.headers().firstValue("X-Private").isEmpty());
            assertEquals(exchange.getResponseHeaders().getFirst("X-Request-Id"),
                    request.headers().firstValue("X-Request-Id").orElseThrow());
        }
        assertEquals(method.equals("HEAD") ? "" : "成功", exchange.response.toString(StandardCharsets.UTF_8));
        if (failure.equals("protocol")) {
            assertTrue(firstBodyClosed.get());
        }
        AccessLogger.Event event = event(exchange, "b", 2, "NONE");
        assertEquals("/files/a%2Fb", event.path());
        assertFalse(event.format().contains("secret"));
        assertEquals("192.0.2.1", exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
    }

    static Stream<Arguments> retryableMethodsAndFailures() {
        return Stream.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS")
                .flatMap(method -> Stream.of("connect", "io", "timeout", "connect-timeout", "protocol")
                        .map(failure -> Arguments.of(method, failure)));
    }

    @ParameterizedTest
    @MethodSource("nonRetryableMethodsAndFailures")
    void neverReplaysUnsafeUnknownOrDifferentlyCasedMethods(String method, String failure) {
        MemoryExchange exchange = exchange(method);
        run(configuration(10), exchange, request -> outcome(failure, new AtomicBoolean()));
        int expected = failure.equals("timeout") ? 504 : failure.equals("503") ? 503 : 502;
        assertEquals(expected, exchange.status);
        assertEquals(1, requests.size());
        event(exchange, "a", 1, failure.equals("timeout") ? "UPSTREAM_TIMEOUT"
                : failure.equals("503") ? "UPSTREAM_HTTP_ERROR" : "UPSTREAM_IO_FAILURE");
    }

    static Stream<Arguments> nonRetryableMethodsAndFailures() {
        return Stream.of("POST", "PATCH", "TRACE", "PROPFIND", "get")
                .flatMap(method -> Stream.of("connect", "timeout", "503", "protocol")
                        .map(failure -> Arguments.of(method, failure)));
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void retainsTheLastLegalRetryableResponseAndClosesTheDiscardedBodyBeforeSending(int status) {
        AtomicBoolean discarded = new AtomicBoolean();
        MemoryExchange exchange = exchange("GET");
        run(configuration(2), exchange, request -> {
            if (requests.size() == 1) {
                return new UpstreamResponse(status, Map.of("X-First-Only", List.of("do-not-leak")),
                        unreadBody(discarded, false));
            }
            assertTrue(discarded.get(), "The abandoned body must be closed before the next send");
            return new UpstreamResponse(status, Map.of("X-Final", List.of("yes"),
                    "Retry-After", List.of("10")), new ByteArrayInputStream("最终响应".getBytes(StandardCharsets.UTF_8)));
        });
        assertEquals(status, exchange.status);
        assertEquals("最终响应", exchange.response.toString(StandardCharsets.UTF_8));
        assertNull(exchange.getResponseHeaders().getFirst("X-First-Only"));
        assertEquals("yes", exchange.getResponseHeaders().getFirst("X-Final"));
        assertEquals("10", exchange.getResponseHeaders().getFirst("Retry-After"));
        event(exchange, "b", 2, "UPSTREAM_HTTP_ERROR");
    }

    @ParameterizedTest
    @CsvSource({"timeout,io,502,UPSTREAM_IO_FAILURE", "io,timeout,504,UPSTREAM_TIMEOUT",
            "503,io,502,UPSTREAM_IO_FAILURE", "503,timeout,504,UPSTREAM_TIMEOUT",
            "io,503,503,UPSTREAM_HTTP_ERROR", "timeout,404,404,UPSTREAM_HTTP_ERROR",
            "503,internal,500,INTERNAL_ERROR", "io,illegal-argument,500,INTERNAL_ERROR"})
    void finalOutcomeDeterminesTheStatusNotEarlierFailures(String first, String last, int status, String error) {
        MemoryExchange exchange = exchange("GET");
        run(configuration(2), exchange,
                request -> outcome(requests.size() == 1 ? first : last, new AtomicBoolean()));
        assertEquals(status, exchange.status);
        if (error.equals("UPSTREAM_HTTP_ERROR")) {
            assertEquals("upstream " + last, exchange.response.toString(StandardCharsets.UTF_8));
        }
        event(exchange, "b", 2, error);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 302, 400, 401, 404, 409, 429, 500, 501, 505})
    void immediatelyReturnsResponsesNotInTheConfiguredRetrySet(int status) {
        MemoryExchange exchange = exchange("GET");
        run(configuration(2), exchange, request -> response(status, "original"));
        assertEquals(status, exchange.status);
        assertEquals("original", exchange.response.toString(StandardCharsets.UTF_8));
        event(exchange, "a", 1, status >= 400 ? "UPSTREAM_HTTP_ERROR" : "NONE");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void honorsExplicitErrorStatusOverridesSupportedByTheConfigurationModel(int status) {
        MemoryExchange exchange = exchange("GET");
        ProxyConfig config = retryConfig(configuration(2), 2, Set.of(429, 500));
        run(config, exchange, request -> response(requests.size() == 1 ? status : 200, "ok"));
        assertEquals(200, exchange.status);
        event(exchange, "b", 2, "NONE");
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,2", "10,2"})
    void boundsAttemptsByTheConfiguredLimitAndNumberOfDifferentHealthyBackends(int limit, int expected) {
        MemoryExchange exchange = exchange("GET");
        run(configuration(limit), exchange, request -> { throw new IOException("secret exception"); });
        assertEquals(502, exchange.status);
        assertEquals(expected, requests.size());
        event(exchange, expected == 1 ? "a" : "b", expected, "UPSTREAM_IO_FAILURE");
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void neverSelectsTheSameInstanceTwiceIncludingWeightedRetries(LoadBalancingStrategy strategy) {
        ProxyConfig base = config(List.of(backend("a", 9), backend("b", 3), backend("c", 1), backend("d", 1)),
                1024, Duration.ofSeconds(1), strategy);
        MemoryExchange exchange = exchange("GET");
        run(retryConfig(base, 10, Set.of(503)), exchange, request -> response(503, "unavailable"));
        assertEquals(4, requests.size());
        assertEquals(4, requests.stream().map(request -> request.uri().getRawPath()).distinct().count());
        String finalId = requests.get(3).uri().getPath().split("/")[1];
        event(exchange, finalId, 4, "UPSTREAM_HTTP_ERROR");
    }

    @Test
    void usesOneInitialSnapshotEvenIfHealthMembershipChangesDuringTheFirstAttempt() {
        ProxyConfig config = retryConfig(config(List.of(backend("a", 1), backend("b", 1), backend("c", 1)),
                1024, Duration.ofSeconds(1)), 10, Set.of(503));
        BackendRegistry registry = new BackendRegistry(config);
        registry.recordSuccess("a");
        registry.recordSuccess("b");
        AtomicInteger snapshots = new AtomicInteger();
        BackendPool pool = () -> {
            snapshots.incrementAndGet();
            return registry.candidates();
        };
        MemoryExchange exchange = exchange("GET");
        run(config, pool, LoadBalancer.create(config.loadBalancingStrategy()), exchange, request -> {
            if (requests.size() == 1) {
                registry.recordFailure("a");
                registry.recordFailure("a");
                registry.recordFailure("b");
                registry.recordFailure("b");
                registry.recordSuccess("c");
                throw new ConnectException("a stopped");
            }
            return response(200, "b from original snapshot");
        });
        assertEquals(1, snapshots.get());
        assertEquals(List.of("c"), registry.candidates().stream().map(value -> value.id()).toList());
        assertEquals("/b/files/a%2Fb", requests.get(1).uri().getRawPath());
        event(exchange, "b", 2, "NONE");
    }

    @Test
    void businessFailuresDoNotMutateProbeHealthAndUnknownBackendsAreNeverTried() {
        ProxyConfig config = configuration(10);
        BackendRegistry registry = new BackendRegistry(config);
        MemoryExchange unknown = exchange("GET");
        run(config, registry, LoadBalancer.create(config.loadBalancingStrategy()), unknown,
                request -> { throw new AssertionError("UNKNOWN must not receive traffic"); });
        assertEquals(503, unknown.status);
        event(unknown, "-", 0, "NO_BACKEND");
        logs.clear();
        registry.recordSuccess("a");
        MemoryExchange onlyA = exchange("GET");
        run(config, registry, LoadBalancer.create(config.loadBalancingStrategy()), onlyA,
                request -> { throw new ConnectException("a failed"); });
        assertEquals(502, onlyA.status);
        assertEquals(List.of("a"), registry.candidates().stream().map(value -> value.id()).toList());
        assertEquals(0, registry.candidates().get(0).consecutiveFailures());
        event(onlyA, "a", 1, "UPSTREAM_IO_FAILURE");
    }

    @Test
    void emptyStatusSetStillPermitsIoRetries() {
        ProxyConfig config = retryConfig(configuration(2), 2, Set.of());
        MemoryExchange original = exchange("GET");
        run(config, original, request -> response(503, "original"));
        event(original, "a", 1, "UPSTREAM_HTTP_ERROR");
        logs.clear();
        requests.clear();
        MemoryExchange retried = exchange("GET");
        run(config, retried, request -> {
            if (requests.size() == 1) {
                throw new IOException("io failure");
            }
            return response(200, "ok");
        });
        event(retried, "b", 2, "NONE");
    }

    @Test
    void aDiscardCloseFailureDoesNotLoseThePlannedRetryOrLeakIntermediateHeaders() {
        AtomicBoolean closed = new AtomicBoolean();
        MemoryExchange exchange = exchange("GET");
        run(configuration(2), exchange, request -> {
            if (requests.size() == 1) {
                return new UpstreamResponse(503, Map.of(), unreadBody(closed, true));
            }
            assertTrue(closed.get());
            return response(200, "ok");
        });
        event(exchange, "b", 2, "NONE");
    }

    @Test
    void anUpstreamStreamTimeoutAfterCommitDoesNotRetryAndClosesTheResponse() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new InputStream() {
            private boolean first = true;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (first) {
                    first = false;
                    buffer[offset] = 'x';
                    return 1;
                }
                throw new HttpTimeoutException("body read failed after commitment");
            }

            @Override
            public int read() {
                throw new AssertionError("The forwarder should use buffered reads");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        MemoryExchange exchange = exchange("GET");
        run(configuration(2), exchange, request -> new UpstreamResponse(200, Map.of(), body));
        assertTrue(closed.get());
        assertEquals(200, exchange.status);
        assertEquals("x", exchange.response.toString(StandardCharsets.UTF_8));
        event(exchange, "a", 1, "UPSTREAM_STREAM_FAILURE");
    }

    @Test
    void downstreamWriteFailureDoesNotRetry() {
        MemoryExchange exchange = exchange("GET");
        exchange.setStreams(null, new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client went away");
            }
        });
        run(configuration(2), exchange, request -> response(200, "data"));
        event(exchange, "a", 1, "CLIENT_STREAM_FAILURE");
    }

    @Test
    void interruptionsStopRetriesAndPreserveTheThreadInterruptFlag() {
        MemoryExchange exchange = exchange("GET");
        try {
            run(configuration(2), exchange, request -> { throw new InterruptedException("shutdown"); });
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, exchange.commits);
            event(exchange, "a", 1, "INTERRUPTED");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void closingTheForwarderDuringASendPreventsFailover() {
        ProxyConfig config = configuration(2);
        AtomicReference<RequestForwarder> owner = new AtomicReference<>();
        MemoryExchange exchange = exchange("GET");
        try (RequestForwarder forwarder = new RequestForwarder(config, new StaticBackendPool(config.backends()),
                LoadBalancer.create(config.loadBalancingStrategy()), request -> {
                    requests.add(request);
                    owner.get().close();
                    throw new IOException("stopped");
                }, logs::add)) {
            owner.set(forwarder);
            forwarder.handle(exchange);
            assertEquals(1, requests.size());
            assertEquals(1, logs.size());
            assertEquals(1, logs.get(0).attempts());
        }
    }

    @Test
    void selectorFailureClosesAnUncommittedResponseAndIsAnInternalError() {
        AtomicBoolean closed = new AtomicBoolean();
        ProxyConfig config = configuration(2);
        AtomicInteger selections = new AtomicInteger();
        MemoryExchange exchange = exchange("GET");
        run(config, new StaticBackendPool(config.backends()), (candidates, excluded) -> {
            if (selections.incrementAndGet() > 1) {
                throw new IllegalArgumentException("selector defect, not bad client input");
            }
            return java.util.Optional.of(candidates.get(0));
        }, exchange, request -> new UpstreamResponse(503, Map.of(), unreadBody(closed, false)));
        assertTrue(closed.get());
        assertEquals(500, exchange.status);
        event(exchange, "a", 1, "INTERNAL_ERROR");
    }

    @Test
    void oversizedBodyIsRejectedBeforeAnyAttempt() {
        ProxyConfig config = config(List.of(backend("a", 1), backend("b", 1)), 2, Duration.ofSeconds(1));
        MemoryExchange exchange = exchange("PUT");
        run(config, exchange, request -> { throw new AssertionError("Must not send an oversized request"); });
        assertEquals(413, exchange.status);
        event(exchange, "-", 0, "REQUEST_BODY_TOO_LARGE");
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void concurrentRequestsHaveIndependentExclusionsCountersAndFinalLogs(LoadBalancingStrategy strategy)
            throws Exception {
        ProxyConfig config = config(List.of(backend("a", 3), backend("b", 1)),
                1024, Duration.ofSeconds(1), strategy);
        Map<String, List<String>> triedByRequest = new ConcurrentHashMap<>();
        var events = new ConcurrentLinkedQueue<AccessLogger.Event>();
        var executor = Executors.newFixedThreadPool(6);
        try (RequestForwarder forwarder = new RequestForwarder(config, new StaticBackendPool(config.backends()),
                LoadBalancer.create(strategy), request -> {
                    String id = request.headers().firstValue("X-Request-Id").orElseThrow();
                    // Each ID belongs to exactly one request-handling thread.
                    List<String> paths = triedByRequest.computeIfAbsent(id, ignored -> new ArrayList<>());
                    paths.add(request.uri().getRawPath());
                    if (paths.size() == 1) {
                        throw new ConnectException("first attempt failed");
                    }
                    return response(200, "ok");
                }, events::add)) {
            var results = new ArrayList<java.util.concurrent.Future<MemoryExchange>>();
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> {
                    MemoryExchange exchange = exchange("GET");
                    forwarder.handle(exchange);
                    return exchange;
                }));
            }
            for (var result : results) {
                assertEquals(200, result.get(2, TimeUnit.SECONDS).status);
            }
            assertEquals(32, triedByRequest.size());
            assertEquals(32, events.size());
            assertEquals(32, events.stream().map(AccessLogger.Event::requestId).distinct().count());
            for (AccessLogger.Event event : events) {
                List<String> paths = triedByRequest.get(event.requestId());
                assertEquals(2, paths.size());
                assertNotEquals(paths.get(0), paths.get(1));
                assertEquals(paths.get(1).split("/")[1], event.backend());
                assertEquals(2, event.attempts());
                assertEquals("NONE", event.error());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private void run(ProxyConfig config, MemoryExchange exchange, UpstreamTransport transport) {
        run(config, new StaticBackendPool(config.backends()), LoadBalancer.create(config.loadBalancingStrategy()),
                exchange, transport);
    }

    private void run(ProxyConfig config, BackendPool pool, LoadBalancer selector,
            MemoryExchange exchange, UpstreamTransport transport) {
        try (RequestForwarder forwarder = new RequestForwarder(config, pool, selector, request -> {
            requests.add(request);
            return transport.send(request);
        }, logs::add)) {
            forwarder.handle(exchange);
        }
    }

    private AccessLogger.Event event(MemoryExchange exchange, String backend, int attempts, String error) {
        assertTrue(exchange.closed);
        assertEquals(1, logs.size(), "Exactly one final event, not one per attempt");
        AccessLogger.Event event = logs.get(0);
        assertEquals(backend, event.backend());
        assertEquals(attempts, event.attempts());
        assertEquals(attempts, requests.size());
        assertEquals(exchange.status, event.status());
        assertEquals(error, event.error());
        assertEquals(exchange.response.size(), event.responseBytes());
        assertTrue(event.durationMs() >= 0);
        assertNotNull(event.timestamp());
        assertFalse(event.requestId().isBlank());
        if (exchange.commits > 0) {
            assertEquals(1, exchange.commits);
            assertEquals(exchange.getResponseHeaders().getFirst("X-Request-Id"), event.requestId());
        }
        return event;
    }

    private static MemoryExchange exchange(String method) {
        return new MemoryExchange(method, "/files/a%2Fb?token=secret-query&q=a%2Bb", PAYLOAD);
    }

    private static ProxyConfig configuration(int maxAttempts) {
        return retryConfig(config(List.of(backend("a", 1), backend("b", 1)), 1024,
                Duration.ofMillis(321)), maxAttempts, Set.of(502, 503, 504));
    }

    private static BackendConfig backend(String id, int weight) {
        return new BackendConfig(id, URI.create("http://127.0.0.1:9001/" + id), weight, "/health");
    }

    private static UpstreamResponse outcome(String name, AtomicBoolean closed) throws IOException {
        return switch (name) {
            case "connect" -> throw new ConnectException("secret connect detail");
            case "io" -> throw new IOException("secret io detail");
            case "timeout" -> throw new HttpTimeoutException("secret timeout detail");
            case "connect-timeout" -> throw new HttpConnectTimeoutException("secret connect timeout detail");
            case "protocol" -> new UpstreamResponse(101, Map.of(), unreadBody(closed, false));
            case "internal" -> throw new IllegalStateException("secret implementation detail");
            case "illegal-argument" -> throw new IllegalArgumentException("transport bug");
            default -> response(Integer.parseInt(name), "upstream " + name);
        };
    }

    private static UpstreamResponse response(int status, String text) {
        return new UpstreamResponse(status, Map.of(),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static InputStream unreadBody(AtomicBoolean closed, boolean failOnClose) {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("An abandoned response body must not be read");
            }

            @Override
            public void close() throws IOException {
                closed.set(true);
                if (failOnClose) {
                    throw new IOException("close failure");
                }
            }
        };
    }

    private static byte[] bodyOf(HttpRequest request) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                body.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                result.complete(body.toByteArray());
            }
        });
        return result.get(1, TimeUnit.SECONDS);
    }
}
