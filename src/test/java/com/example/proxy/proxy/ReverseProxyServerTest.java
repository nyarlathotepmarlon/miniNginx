package com.example.proxy.proxy;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendPool;
import com.example.proxy.balance.SingleBackendSelector;
import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.transport.JdkHttpTransport;
import com.example.proxy.transport.UpstreamResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ReverseProxyServerTest {
    private final HttpClient client = client();
    private final BlockingQueue<AccessLogger.Event> logs = new LinkedBlockingQueue<>();

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"})
    void forwardsMethodsEncodedUriBinaryBodyAndStatus(String method) throws Exception {
        BlockingQueue<Received> received = new LinkedBlockingQueue<>();
        byte[] payload = {0, 1, (byte) 255, 13, 10};
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
                exchange.getResponseHeaders().add("Set-Cookie", "a=1");
                exchange.getResponseHeaders().add("Set-Cookie", "b=2");
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            }
        }); ReverseProxyServer proxy = start(config(backend.uri("/api/")),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url(proxy, "/files/a%2Fb?q=a%2Bb"))
                    .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            Received upstream = received.poll(2, TimeUnit.SECONDS);
            assertNotNull(upstream);
            assertEquals(method, upstream.method());
            assertEquals("/api/files/a%2Fb?q=a%2Bb", upstream.target());
            assertArrayEquals(payload, upstream.body());
            assertEquals(201, response.statusCode());
            assertArrayEquals(payload, response.body());
            assertEquals(2, response.headers().allValues("Set-Cookie").size());
            assertTrue(response.headers().firstValue("Content-Length").isEmpty());
            AccessLogger.Event event = logs.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(1, event.attempts());
            assertEquals(payload.length, event.responseBytes());
            assertEquals(response.headers().firstValue("X-Request-Id").orElseThrow(), event.requestId());
            assertEquals("NONE", event.error());
            assertTrue(logs.isEmpty());
        }
    }

    @Test
    void filtersHeadersInBothDirectionsAndRebuildsForwardingMetadata() throws Exception {
        BlockingQueue<com.sun.net.httpserver.Headers> received = new LinkedBlockingQueue<>();
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                received.add(exchange.getRequestHeaders());
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Connection", "X-Backend-Private, close");
                exchange.getResponseHeaders().set("X-Backend-Private", "must-not-escape");
                exchange.getResponseHeaders().set("Keep-Alive", "timeout=5");
                exchange.getResponseHeaders().set("X-Public", "yes");
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[] {'o', 'k'});
            }
        }); ReverseProxyServer proxy = start(config(backend.uri("")),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            String response = rawRequest(proxy, "POST /data?token=secret-query HTTP/1.1\r\nHost: original.example\r\n"
                    + "Connection: X-Secret, close\r\nX-Secret: private\r\nKeep-Alive: timeout=9\r\n"
                    + "Proxy-Authorization: hidden\r\nAuthorization: Bearer business-token\r\n"
                    + "Cookie: session=secret-cookie\r\nX-Forwarded-For: 192.0.2.1\r\n"
                    + "X-Forwarded-For: 198.51.100.1\r\nX-Forwarded-Proto: https\r\n"
                    + "X-Request-Id: spoofed\r\nContent-Length: 3\r\n\r\nabc");
            var headers = received.poll(2, TimeUnit.SECONDS);
            assertNotNull(headers);
            assertNull(headers.getFirst("X-Secret"));
            assertNull(headers.getFirst("Keep-Alive"));
            assertNull(headers.getFirst("Proxy-Authorization"));
            assertEquals("Bearer business-token", headers.getFirst("Authorization"));
            assertEquals("192.0.2.1, 198.51.100.1, 127.0.0.1", headers.getFirst("X-Forwarded-For"));
            assertEquals("http", headers.getFirst("X-Forwarded-Proto"));
            assertEquals("original.example", headers.getFirst("X-Forwarded-Host"));
            assertNotEquals("original.example", headers.getFirst("Host"));
            assertNotEquals("spoofed", headers.getFirst("X-Request-Id"));
            assertEquals("3", headers.getFirst("Content-Length"));
            String lower = response.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("x-backend-private"));
            assertFalse(lower.contains("keep-alive"));
            assertFalse(lower.contains("content-length"));
            assertTrue(lower.contains("x-public: yes"));
            assertTrue(lower.contains("transfer-encoding: chunked"));
            AccessLogger.Event event = logs.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("/data", event.path());
            assertFalse(event.format().contains("business-token"));
            assertFalse(event.format().contains("secret-cookie"));
            assertFalse(event.format().contains("secret-query"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a/../b", "/a/./b", "//files/a", "/_proxy-other", "/_proxy%2Fhealth"})
    void preservesSpecialTargetsAndManagementBoundaryOnTheWire(String path) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                received.add(exchange.getRequestURI().toString());
                exchange.sendResponseHeaders(204, -1);
            }
        }); ReverseProxyServer proxy = start(config(backend.uri("/api")),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            String response = rawRequest(proxy,
                    "GET " + path + " HTTP/1.1\r\nHost: proxy\r\nConnection: close\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 204"), response);
            assertEquals("/api" + path, received.poll(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 304})
    void sendsNoBodyForBodylessStatusCodes(int status) throws Exception {
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(status, -1);
            }
        }); ReverseProxyServer proxy = start(config(backend.uri("")),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            HttpResponse<byte[]> response = get(proxy, "/empty");
            assertEquals(status, response.statusCode());
            assertEquals(0, response.body().length);
            assertTrue(response.headers().firstValue("Transfer-Encoding").isEmpty());
        }
    }

    @Test
    void handlesHeadEmptyResponsesAndRedirects() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Backend backend = new Backend(exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", "123");
                    exchange.sendResponseHeaders(200, -1);
                } else if (exchange.getRequestURI().getPath().equals("/redirect")) {
                    exchange.getResponseHeaders().set("Location", "/destination");
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    exchange.sendResponseHeaders(200, -1);
                }
            }
        }); ReverseProxyServer proxy = start(config(backend.uri("")),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            HttpResponse<byte[]> head = client.send(HttpRequest.newBuilder(url(proxy, "/head"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            assertEquals(0, get(proxy, "/empty").body().length);
            HttpResponse<byte[]> redirect = get(proxy, "/redirect");
            assertEquals(302, redirect.statusCode());
            assertEquals("/destination", redirect.headers().firstValue("Location").orElseThrow());
            assertEquals(3, calls.get());
        }
    }

    @Test
    void enforcesConfiguredLimitWithAndWithoutContentLengthBeforeForwarding() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ReverseProxyServer proxy = start(config(URI.create("http://127.0.0.1:9001"), 4,
                Duration.ofSeconds(1)), request -> {
                    calls.incrementAndGet();
                    return new UpstreamResponse(200, Map.of(), InputStream.nullInputStream());
                }, logs::add)) {
            for (HttpRequest.BodyPublisher publisher : List.of(
                    HttpRequest.BodyPublishers.ofString("12345"),
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("12345".getBytes())))) {
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url(proxy, "/body"))
                        .POST(publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(413, response.statusCode());
            }
            assertEquals(0, calls.get());
            assertEquals(413, logs.poll(2, TimeUnit.SECONDS).status());
            assertEquals(0, logs.poll(2, TimeUnit.SECONDS).attempts());
            HttpResponse<byte[]> boundary = client.send(HttpRequest.newBuilder(url(proxy, "/body"))
                    .POST(HttpRequest.BodyPublishers.ofString("1234")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, boundary.statusCode());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void rejectsDefaultLimitOfTenMiBPlusOneWithoutSendingUpstream() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ReverseProxyServer proxy = start(config(URI.create("http://127.0.0.1:9001")), request -> {
            calls.incrementAndGet();
            throw new AssertionError("Oversized request forwarded");
        }, logs::add)) {
            String result = rawRequest(proxy, "POST /large HTTP/1.1\r\nHost: test\r\n"
                    + "Content-Length: 10485761\r\nConnection: close\r\n\r\n");
            assertTrue(result.startsWith("HTTP/1.1 413"), result);
            assertEquals(0, calls.get());
        }
    }

    @Test
    void managementReportsStaticEligibilityAndDoesNotContactUpstream() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ReverseProxyServer proxy = start(config(URI.create("http://127.0.0.1:9001")), request -> {
            calls.incrementAndGet();
            throw new AssertionError("Management request forwarded");
        }, logs::add)) {
            HttpResponse<byte[]> healthResponse = get(proxy, "/_proxy/health");
            assertEquals("close", healthResponse.headers().firstValue("Connection").orElseThrow());
            String health = new String(healthResponse.body(), StandardCharsets.UTF_8);
            assertTrue(health.contains("\"healthChecksEnabled\":false"));
            assertTrue(health.contains("\"eligibleBackends\":1"));
            assertEquals(404, get(proxy, "/_proxy").statusCode());
            assertEquals(404, get(proxy, "/_proxy/missing").statusCode());
            assertEquals(404, get(proxy, "/_proxy/health/extra").statusCode());
            HttpResponse<byte[]> method = client.send(HttpRequest.newBuilder(url(proxy, "/_proxy/health"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, method.statusCode());
            assertEquals("GET", method.headers().firstValue("Allow").orElseThrow());
            // Management does not wait for an upload and must not offer reuse of an unread socket.
            String unread = rawRequest(proxy, "GET /_proxy/health HTTP/1.1\r\nHost: proxy\r\n"
                    + "Content-Length: 999\r\n\r\n");
            assertTrue(unread.startsWith("HTTP/1.1 200"), unread);
            assertTrue(unread.toLowerCase(java.util.Locale.ROOT).contains("connection: close"), unread);
            assertEquals(0, calls.get());
        }
    }

    @Test
    void emptyPoolReturns503AndUnsupportedUpgradeIsRejected() throws Exception {
        BackendPool empty = List::of;
        try (ReverseProxyServer proxy = ReverseProxyServer.create(config(URI.create("http://localhost:9001")),
                empty, new SingleBackendSelector(), request -> { throw new AssertionError(); }, logs::add)) {
            proxy.start();
            assertEquals(503, get(proxy, "/business").statusCode());
            assertEquals(503, get(proxy, "/_proxy/health").statusCode());
            String upgraded = rawRequest(proxy, "GET /ws HTTP/1.1\r\nHost: test\r\n"
                    + "Connection: upgrade, close\r\nUpgrade: websocket\r\n\r\n");
            assertTrue(upgraded.startsWith("HTTP/1.1 501"));
        }
    }

    @Test
    void returns504WhenUpstreamHeadersAreLateAndDoesNotRetry() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (Backend backend = new Backend(exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        }); ReverseProxyServer proxy = start(config(backend.uri(""), 1024, Duration.ofMillis(250)),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            assertEquals(504, get(proxy, "/slow").statusCode());
            assertEquals(1, calls.get());
            assertEquals("UPSTREAM_TIMEOUT", logs.poll(2, TimeUnit.SECONDS).error());
        } finally {
            release.countDown();
        }
    }

    @Test
    void preservesLastLegal503AndMapsTransportFailureTo502WithOneAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ReverseProxyServer proxy = start(config(URI.create("http://localhost:9001")), request -> {
            if (calls.incrementAndGet() == 1) {
                return new UpstreamResponse(503, Map.of("Retry-After", List.of("30")),
                        new ByteArrayInputStream("backend unavailable".getBytes(StandardCharsets.UTF_8)));
            }
            throw new IOException("connection refused");
        }, logs::add)) {
            HttpResponse<byte[]> first = get(proxy, "/status");
            assertEquals(503, first.statusCode());
            assertEquals("30", first.headers().firstValue("Retry-After").orElseThrow());
            assertEquals("backend unavailable", new String(first.body(), StandardCharsets.UTF_8));
            assertEquals(1, calls.get());
            assertEquals(502, get(proxy, "/fail").statusCode());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void abortsTruncatedChunkedResponseWithoutRetryOrSuccessfulTerminalChunk() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        InputStream failingBody = new InputStream() {
            private boolean emitted;
            @Override
            public int read() throws IOException {
                throw new IOException("truncated");
            }
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (emitted) {
                    throw new IOException("truncated");
                }
                emitted = true;
                bytes[offset] = 'x';
                return 1;
            }
            @Override
            public void close() {
                closed.set(true);
            }
        };
        ProxyConfig config = config(List.of(
                new BackendConfig("a", URI.create("http://localhost:9001"), 1, "/health"),
                new BackendConfig("b", URI.create("http://localhost:9002"), 1, "/health")),
                1024, Duration.ofSeconds(2));
        try (ReverseProxyServer proxy = start(config, request -> {
            calls.incrementAndGet();
            return new UpstreamResponse(200, Map.of(), failingBody);
        }, logs::add)) {
            String response = rawRequest(proxy, "GET /broken HTTP/1.1\r\nHost: test\r\nConnection: close\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 200"));
            assertTrue(response.endsWith("1\r\nx\r\n"), response);
            assertFalse(response.endsWith("0\r\n\r\n"), response);
            AccessLogger.Event event = logs.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("UPSTREAM_STREAM_FAILURE", event.error());
            assertEquals(1, event.responseBytes());
            assertEquals(1, calls.get());
            assertTrue(closed.get());
        }
    }

    @Test
    void streamsFirstChunkBeforeTheRestOfTheBodyIsAvailable() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return;
                }
                exchange.getResponseBody().write('b');
            }
        }); ReverseProxyServer proxy = start(config(backend.uri("")),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(url(proxy, "/stream"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertEquals('a', body.read());
                release.countDown();
                assertEquals('b', body.read());
                assertEquals(-1, body.read());
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void closesRepeatedlyReleasesPortAndRejectsRestart() throws Exception {
        try (ReverseProxyServer proxy = start(config(URI.create("http://localhost:9001")),
                request -> new UpstreamResponse(200, Map.of(), InputStream.nullInputStream()), logs::add)) {
            URI address = url(proxy, "/ready");
            assertEquals(200, get(proxy, "/ready").statusCode());
            proxy.close();
            proxy.close();
            assertThrows(IllegalStateException.class, proxy::start);
            assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(address)
                    .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding()));
        }
    }

    @Test
    void defaultFactoryUsesAllConfiguredBackends() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        try (Backend first = new Backend(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (!exchange.getRequestURI().getPath().equals("/health")) {
                    firstCalls.incrementAndGet();
                }
                exchange.sendResponseHeaders(204, -1);
            }
        }); Backend second = new Backend(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (!exchange.getRequestURI().getPath().equals("/health")) {
                    secondCalls.incrementAndGet();
                }
                exchange.sendResponseHeaders(204, -1);
            }
        }); ReverseProxyServer proxy = ReverseProxyServer.create(config(List.of(
                new BackendConfig("first", first.uri(""), 1, "/health"),
                new BackendConfig("second", second.uri(""), 9, "/health")), 1024, Duration.ofSeconds(2)))) {
            proxy.start();
            proxy.start();
            awaitHealthy(client, proxy, 2);
            for (int i = 0; i < 4; i++) {
                assertEquals(204, get(proxy, "/business").statusCode());
            }
            String health = new String(get(proxy, "/_proxy/health").body(), StandardCharsets.UTF_8);
            assertTrue(health.contains("\"totalBackends\":2"));
            assertTrue(health.contains("\"eligibleBackends\":2"));
            assertTrue(health.contains("\"loadBalancingStrategy\":\"round-robin\""));
            assertEquals(2, firstCalls.get());
            assertEquals(2, secondCalls.get());
        }
    }

    @Test
    void closingProxyCancelsAnActiveStreamAndTerminatesItsWorkers() throws Exception {
        CountDownLatch releaseBackend = new CountDownLatch(1);
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                try {
                    releaseBackend.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        }); ReverseProxyServer proxy = start(config(backend.uri("")),
                new JdkHttpTransport(Duration.ofSeconds(1)), logs::add)) {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(url(proxy, "/stream"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertEquals('a', body.read());
                List<Thread> workers = Thread.getAllStackTraces().keySet().stream()
                        .filter(thread -> thread.getName().matches("proxy-\\d+-worker-\\d+"))
                        .toList();
                assertFalse(workers.isEmpty());
                proxy.close();
                assertTrue(workers.stream().noneMatch(Thread::isAlive));
                assertThrows(IOException.class, body::read);
                AccessLogger.Event event = logs.poll(2, TimeUnit.SECONDS);
                assertNotNull(event);
                assertEquals(200, event.status());
                assertNotEquals("NONE", event.error());
                assertTrue(logs.isEmpty());
            } finally {
                releaseBackend.countDown();
            }
        } finally {
            releaseBackend.countDown();
        }
    }

    private HttpResponse<byte[]> get(ReverseProxyServer proxy, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(proxy, path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private record Received(String method, String target, byte[] body) {
    }
}
