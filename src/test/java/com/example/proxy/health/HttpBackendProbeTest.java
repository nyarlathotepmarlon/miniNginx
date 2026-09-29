package com.example.proxy.health;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.config.BackendConfig;
import com.example.proxy.transport.JdkHttpTransport;
import com.example.proxy.transport.UpstreamResponse;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class HttpBackendProbeTest {
    @ParameterizedTest
    @ValueSource(ints = {199, 200, 204, 299, 300, 302, 399, 400, 404, 500, 503})
    void appliesStatusRangeRawUriAndTimeoutAndAlwaysClosesWithoutReadingBody(int status) throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("A health probe must not read the response body");
            }
            @Override
            public void close() {
                closed.set(true);
            }
        };
        BackendConfig backend = new BackendConfig("a", URI.create("http://127.0.0.1:9001/api%2Fv1/"), 1,
                "/health%2Flive");
        HttpBackendProbe probe = new HttpBackendProbe(request -> {
            assertEquals("GET", request.method());
            assertEquals("/api%2Fv1/health%2Flive", request.uri().getRawPath());
            assertNull(request.uri().getRawQuery());
            assertEquals(Duration.ofMillis(123), request.timeout().orElseThrow());
            return new UpstreamResponse(status, Map.of(), body);
        }, Duration.ofMillis(123));
        assertEquals(status >= 200 && status <= 399, probe.check(backend));
        assertTrue(closed.get());
    }

    @Test
    void treatsRedirectAsSuccessWithoutFollowingLocation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                exchange.getResponseHeaders().set("Location", "/must-not-follow");
                exchange.sendResponseHeaders(302, -1);
            }
        })) {
            HttpBackendProbe probe = new HttpBackendProbe(new JdkHttpTransport(Duration.ofSeconds(1)), Duration.ofSeconds(1));
            assertTrue(probe.check(new BackendConfig("a", backend.uri("/prefix"), 1, "/health")));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void boundsWaitingForHeadersWithTheHealthSpecificTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        })) {
            HttpBackendProbe probe = new HttpBackendProbe(new JdkHttpTransport(Duration.ofSeconds(1)), Duration.ofMillis(200));
            assertThrows(HttpTimeoutException.class,
                    () -> probe.check(new BackendConfig("a", backend.uri(""), 1, "/health")));
            assertEquals(0, entered.getCount());
        } finally {
            release.countDown();
        }
    }

    @Test
    void completesAfterHeadersEvenIfResponseBodyHasNotFinished() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean bodyFinished = new AtomicBoolean();
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } finally {
                    bodyFinished.set(true);
                }
            }
        })) {
            HttpBackendProbe probe = new HttpBackendProbe(new JdkHttpTransport(Duration.ofSeconds(1)), Duration.ofSeconds(1));
            assertTrue(probe.check(new BackendConfig("a", backend.uri(""), 1, "/health")));
            assertEquals(1, release.getCount());
            assertFalse(bodyFinished.get());
        } finally {
            release.countDown();
        }
    }
}
