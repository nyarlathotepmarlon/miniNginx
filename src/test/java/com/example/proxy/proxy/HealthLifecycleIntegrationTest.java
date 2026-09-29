package com.example.proxy.proxy;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendState;
import com.example.proxy.backend.BackendStatusUpdate;
import com.example.proxy.balance.LoadBalancingStrategy;
import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ConfigLoader;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.health.HttpBackendProbe;
import com.example.proxy.transport.JdkHttpTransport;
import com.example.proxy.transport.UpstreamResponse;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(25)
class HealthLifecycleIntegrationTest {
    private final HttpClient client = client();

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void automaticallyRemovesStoppedBackendAndRestoresItAfterRestart(LoadBalancingStrategy strategy) throws Exception {
        List<String> businessHits = new CopyOnWriteArrayList<>();
        List<BackendStatusUpdate> transitions = new CopyOnWriteArrayList<>();
        CountDownLatch restored = new CountDownLatch(1);
        try (Backend a = backend("a", new InetSocketAddress("127.0.0.1", 0), businessHits);
                Backend b = backend("b", new InetSocketAddress("127.0.0.1", 0), businessHits)) {
            ProxyConfig config = healthConfig(config(List.of(
                    new BackendConfig("a", a.uri("/api"), 3, "/health"),
                    new BackendConfig("b", b.uri("/api"), 1, "/health")), 1024, Duration.ofSeconds(2), strategy),
                    Duration.ofMillis(40), Duration.ofMillis(200), 2, 2);
            JdkHttpTransport transport = new JdkHttpTransport(config.connectTimeout());
            try (ReverseProxyServer proxy = ReverseProxyServer.createWithHealthChecks(config,
                    new HttpBackendProbe(transport, config.healthTimeout()), transport, event -> {}, update -> {
                        transitions.add(update);
                        if (update.current().id().equals("a") && update.previous().state() == BackendState.UNHEALTHY
                                && update.current().state() == BackendState.HEALTHY) {
                            restored.countDown();
                        }
                    })) {
                proxy.start();
                awaitHealthy(client, proxy, 2);
                List<String> expected = strategy == LoadBalancingStrategy.ROUND_ROBIN
                        ? List.of("a", "b", "a", "b") : List.of("a", "a", "b", "a");
                assertEquals(expected, businessRequests(proxy, 4));
                InetSocketAddress originalAddress = a.address();
                a.close();
                awaitHealthy(client, proxy, 1);
                assertEquals(List.of("b", "b", "b", "b"), businessRequests(proxy, 4));

                try (Backend restarted = backend("a", originalAddress, businessHits)) {
                    awaitHealthy(client, proxy, 2);
                    assertTrue(restored.await(3, TimeUnit.SECONDS));
                    assertEquals(expected, businessRequests(proxy, 4));
                    assertEquals(List.of(BackendState.HEALTHY, BackendState.UNHEALTHY, BackendState.HEALTHY),
                            transitions.stream().filter(update -> update.current().id().equals("a"))
                                    .map(update -> update.current().state()).toList());
                    assertEquals(12, businessHits.size());
                }
                b.close();
                awaitHealthy(client, proxy, 0);
                assertEquals(503, get(proxy, "/data").statusCode());
                assertEquals(12, businessHits.size(), "No requests may reach unavailable backends");
            }
        }
    }

    @Test
    void returns503UntilTheFirstProbeCompletesAndTheSuccessThresholdIsMet() throws Exception {
        ProxyConfig config = healthConfig(config(URI.create("http://127.0.0.1:9001")),
                Duration.ofMillis(20), Duration.ofSeconds(1), 2, 2);
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger upstreamCalls = new AtomicInteger();
        Set<Thread> ownedThreads = ConcurrentHashMap.newKeySet();
        try (ReverseProxyServer proxy = ReverseProxyServer.createWithHealthChecks(config, backend -> {
            ownedThreads.add(Thread.currentThread());
            probeEntered.countDown();
            release.await();
            return true;
        }, request -> {
            ownedThreads.add(Thread.currentThread());
            upstreamCalls.incrementAndGet();
            return new UpstreamResponse(204, Map.of(), InputStream.nullInputStream());
        }, event -> {}, update -> {})) {
            proxy.start();
            assertTrue(probeEntered.await(3, TimeUnit.SECONDS));
            assertEquals(503, get(proxy, "/data").statusCode());
            HttpResponse<String> health = get(proxy, "/_proxy/health");
            assertEquals(503, health.statusCode());
            assertTrue(health.body().contains("\"healthChecksEnabled\":true"));
            assertTrue(health.body().contains("\"healthyBackends\":0"));
            assertEquals(0, upstreamCalls.get());
            release.countDown();
            awaitHealthy(client, proxy, 1);
            assertEquals(204, get(proxy, "/data").statusCode());
            proxy.close();
            proxy.close();
            proxy.awaitTermination();
            assertTrue(ownedThreads.stream().noneMatch(Thread::isAlive));
            assertEquals(1, upstreamCalls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeCancelsARealBlockedProbeWithoutRecordingAFailureAndReleasesThePort() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Set<Thread> probeThreads = ConcurrentHashMap.newKeySet();
        List<BackendStatusUpdate> updates = new CopyOnWriteArrayList<>();
        try (Backend backend = new Backend(exchange -> {
            try (exchange) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        })) {
            ProxyConfig config = healthConfig(config(backend.uri("")), Duration.ofSeconds(5), Duration.ofSeconds(30), 1, 1);
            JdkHttpTransport transport = new JdkHttpTransport(config.connectTimeout());
            HttpBackendProbe probe = new HttpBackendProbe(transport, config.healthTimeout());
            try (ReverseProxyServer proxy = ReverseProxyServer.createWithHealthChecks(config, candidate -> {
                probeThreads.add(Thread.currentThread());
                return probe.check(candidate);
            }, transport, event -> {}, updates::add)) {
                proxy.start();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(503, get(proxy, "/data").statusCode());
                InetSocketAddress address = proxy.address();
                proxy.close();
                assertTrue(updates.isEmpty());
                assertEquals(1, probeThreads.size());
                assertTrue(probeThreads.stream().noneMatch(Thread::isAlive));
                try (Backend rebound = new Backend(address, exchange -> exchange.close())) {
                    assertEquals(address.getPort(), rebound.address().getPort());
                }
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void healthProbesContinueWhenAllBusinessWorkersAreOccupied() throws Exception {
        ProxyConfig config = healthConfig(config(URI.create("http://127.0.0.1:9001")),
                Duration.ofMillis(20), Duration.ofSeconds(1), 1, 1);
        CountDownLatch businessEntered = new CountDownLatch(config.workerThreads());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch furtherProbes = new CountDownLatch(3);
        AtomicBoolean saturated = new AtomicBoolean();
        try (ReverseProxyServer proxy = ReverseProxyServer.createWithHealthChecks(config, backend -> {
            if (saturated.get()) {
                furtherProbes.countDown();
            }
            return true;
        }, request -> {
            businessEntered.countDown();
            release.await();
            return new UpstreamResponse(204, Map.of(), InputStream.nullInputStream());
        }, event -> {}, update -> {})) {
            proxy.start();
            awaitHealthy(client, proxy, 1);
            List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
            for (int i = 0; i < config.workerThreads(); i++) {
                requests.add(client.sendAsync(HttpRequest.newBuilder(url(proxy, "/slow"))
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()));
            }
            assertTrue(businessEntered.await(3, TimeUnit.SECONDS));
            saturated.set(true);
            assertTrue(furtherProbes.await(3, TimeUnit.SECONDS));
            release.countDown();
            for (var request : requests) {
                assertEquals(204, request.get(3, TimeUnit.SECONDS).statusCode());
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void aBindFailureClosesOwnedComponentsWithoutStartingProbes() throws Exception {
        try (Backend occupied = new Backend(exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        })) {
            Properties properties = new Properties();
            properties.setProperty("listen.port", Integer.toString(occupied.address().getPort()));
            properties.setProperty("backends", "a");
            properties.setProperty("backend.a.url", occupied.uri("").toString());
            ProxyConfig config = ConfigLoader.fromProperties(properties);
            try (ReverseProxyServer proxy = ReverseProxyServer.createWithHealthChecks(config,
                    backend -> { throw new AssertionError("Probe started after bind failure"); },
                    request -> { throw new AssertionError(); }, event -> {}, update -> {})) {
                assertThrows(java.net.BindException.class, proxy::start);
                proxy.close();
                assertThrows(IllegalStateException.class, proxy::start);
                assertEquals(204, client.send(HttpRequest.newBuilder(occupied.uri("/still-owned"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        }
    }

    @Test
    void concurrentStartAndCloseAreIdempotentAndCloseBeforeStartDoesNotBind() throws Exception {
        ProxyConfig config = config(URI.create("http://127.0.0.1:9001"));
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        try (ReverseProxyServer proxy = ReverseProxyServer.createWithHealthChecks(config, backend -> {
            threads.add(Thread.currentThread());
            return true;
        }, request -> { throw new AssertionError("No business traffic expected"); }, event -> {}, update -> {})) {
            var executor = Executors.newFixedThreadPool(6);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    boolean shouldStart = i % 2 == 0;
                    tasks.add(executor.submit(() -> {
                        assertTrue(start.await(3, TimeUnit.SECONDS));
                        if (shouldStart) {
                            try {
                                proxy.start();
                            } catch (IllegalStateException expectedAfterClose) {
                                assertTrue(expectedAfterClose.getMessage().contains("closed"));
                            }
                        } else {
                            proxy.close();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get(6, TimeUnit.SECONDS);
                }
                proxy.close();
                assertTrue(threads.stream().noneMatch(Thread::isAlive));
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
        try (ReverseProxyServer unused = ReverseProxyServer.createWithHealthChecks(config,
                backend -> { throw new AssertionError("Closed-before-start proxy must not probe"); },
                request -> { throw new AssertionError(); }, event -> {}, update -> {})) {
            InetSocketAddress address = unused.address();
            assertEquals(0, address.getPort(), "No socket should be allocated until start");
            unused.close();
            assertThrows(IllegalStateException.class, unused::start);
            try (Backend rebound = new Backend(address, exchange -> exchange.close())) {
                assertTrue(rebound.address().getPort() > 0);
            }
        }
    }

    private Backend backend(String id, InetSocketAddress address, List<String> businessHits) throws Exception {
        return new Backend(address, exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (!exchange.getRequestURI().getRawPath().equals("/api/health")) {
                    businessHits.add(id);
                }
                exchange.getResponseHeaders().set("X-Backend-Id", id);
                exchange.sendResponseHeaders(204, -1);
            }
        });
    }

    private List<String> businessRequests(ReverseProxyServer proxy, int count) throws Exception {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HttpResponse<String> response = get(proxy, "/data");
            assertEquals(204, response.statusCode());
            selected.add(response.headers().firstValue("X-Backend-Id").orElseThrow());
        }
        return selected;
    }

    private HttpResponse<String> get(ReverseProxyServer proxy, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(proxy, path)).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
