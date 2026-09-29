package com.example.proxy.proxy;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.health.HttpBackendProbe;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.transport.JdkHttpTransport;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class RetryIntegrationTest {
    private final HttpClient client = client();
    private final BlockingQueue<AccessLogger.Event> logs = new LinkedBlockingQueue<>();

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PATCH"})
    void countsActualWireRequestsWhenAClosesWithoutResponseHeaders(String method) throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        byte[] payload = "可重放的正文\u0000".getBytes(StandardCharsets.UTF_8);
        try (Backend a = new Backend(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                firstCalls.incrementAndGet();
                // The peer consumed the entire request; close without sending response headers.
            }
        }); Backend b = new Backend(exchange -> {
            try (exchange) {
                secondCalls.incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        })) {
            ProxyConfig config = configuration(a, b, Duration.ofSeconds(2));
            try (ReverseProxyServer proxy = start(config, new JdkHttpTransport(config.connectTimeout()), logs::add)) {
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url(proxy, "/disconnect"))
                        .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build(), HttpResponse.BodyHandlers.ofByteArray());
                boolean retryable = method.equals("GET");
                assertEquals(retryable ? 200 : 502, response.statusCode());
                if (retryable) {
                    assertArrayEquals(payload, response.body());
                }
                assertEquals(1, firstCalls.get(), "No hidden JDK replay on the first backend");
                assertEquals(retryable ? 1 : 0, secondCalls.get());
                AccessLogger.Event event = businessEvent("/disconnect");
                assertEquals(retryable ? 2 : 1, event.attempts());
                assertEquals(retryable ? "b" : "a", event.backend());
                assertEquals(retryable ? "NONE" : "UPSTREAM_IO_FAILURE", event.error());
                assertTrue(logs.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PATCH"})
    void slowButHealthyAAllowsOnlyIdempotentFailoverToB(String method) throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try (Backend a = new Backend(exchange -> {
            try (exchange) {
                if (exchange.getRequestURI().getPath().equals("/a/health")) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                firstCalls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        }); Backend b = new Backend(exchange -> {
            try (exchange) {
                if (exchange.getRequestURI().getPath().equals("/b/health")) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                secondCalls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[] {'o', 'k'});
            }
        })) {
            ProxyConfig config = healthConfig(configuration(a, b, Duration.ofMillis(500)),
                    Duration.ofMillis(100), Duration.ofSeconds(1), 2, 1);
            JdkHttpTransport transport = new JdkHttpTransport(config.connectTimeout());
            try (ReverseProxyServer proxy = ReverseProxyServer.createWithHealthChecks(config,
                    new HttpBackendProbe(transport, config.healthTimeout()), transport, logs::add, ignored -> {})) {
                proxy.start();
                try {
                    awaitHealthy(client, proxy, 2);
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder(url(proxy, "/slow"))
                            .timeout(Duration.ofSeconds(4)).method(method, HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.ofString());
                    boolean retryable = method.equals("GET");
                    assertEquals(retryable ? 200 : 504, response.statusCode());
                    if (retryable) {
                        assertEquals("ok", response.body());
                    }
                    assertEquals(1, firstCalls.get());
                    assertEquals(retryable ? 1 : 0, secondCalls.get());
                    AccessLogger.Event event = businessEvent("/slow");
                    assertEquals(retryable ? 2 : 1, event.attempts());
                    assertEquals(retryable ? "b" : "a", event.backend());
                    assertEquals(retryable ? "NONE" : "UPSTREAM_TIMEOUT", event.error());
                    assertEquals(response.statusCode(), event.status());
                    assertTrue(logs.stream().noneMatch(log -> log.path().equals("/slow")));
                    // A's business endpoint timed out, but its independently probed health is still UP.
                    awaitHealthy(client, proxy, 2);
                } finally {
                    release.countDown();
                }
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void headerTimeoutDoesNotClaimToBoundTheFullStreamingBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        try (Backend a = new Backend(exchange -> {
            try (exchange) {
                firstCalls.incrementAndGet();
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
        }); Backend b = new Backend(exchange -> {
            try (exchange) {
                secondCalls.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            }
        })) {
            ProxyConfig config = configuration(a, b, Duration.ofMillis(500));
            try (ReverseProxyServer proxy = start(config, new JdkHttpTransport(config.connectTimeout()), logs::add)) {
                try {
                    HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(url(proxy, "/stream"))
                            .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        assertEquals(200, response.statusCode());
                        assertEquals('a', body.read());
                        // Deliberately exceed the configured header timeout after receiving the first chunk.
                        assertFalse(release.await(700, TimeUnit.MILLISECONDS));
                        release.countDown();
                        assertEquals('b', body.read());
                        assertEquals(-1, body.read());
                    }
                    assertEquals(1, firstCalls.get());
                    assertEquals(0, secondCalls.get());
                    AccessLogger.Event event = businessEvent("/stream");
                    assertEquals(1, event.attempts());
                    assertEquals(2, event.responseBytes());
                    assertEquals("NONE", event.error());
                } finally {
                    release.countDown();
                }
            }
        } finally {
            release.countDown();
        }
    }

    private AccessLogger.Event businessEvent(String path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AccessLogger.Event event = logs.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.path().equals(path)) {
                return event;
            }
        }
        throw new AssertionError("No final access log for " + path);
    }

    private static ProxyConfig configuration(Backend a, Backend b, Duration timeout) {
        return config(List.of(new BackendConfig("a", a.uri("/a"), 3, "/health"),
                new BackendConfig("b", b.uri("/b"), 1, "/health")), 1024, timeout);
    }
}
