package com.example.proxy.proxy;

import static com.example.proxy.support.ProxyTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.example.proxy.backend.BackendRegistry;
import com.example.proxy.backend.StaticBackendPool;
import com.example.proxy.balance.LoadBalancingStrategy;
import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.transport.JdkHttpTransport;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(20)
class LoadBalancingIntegrationTest {
    private final HttpClient client = client();
    private final BlockingQueue<Hit> hits = new LinkedBlockingQueue<>();
    private final BlockingQueue<AccessLogger.Event> logs = new LinkedBlockingQueue<>();

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void distributesRealHttpRequestsAccordingToTheConfiguredStrategy(LoadBalancingStrategy strategy)
            throws Exception {
        try (Backend a = backend("a", 204); Backend b = backend("b", 204)) {
            ProxyConfig config = configuration(a, b, strategy);
            try (ReverseProxyServer proxy = ReverseProxyServer.create(config,
                    new StaticBackendPool(config.backends()), new JdkHttpTransport(config.connectTimeout()), logs::add)) {
                proxy.start();
                HttpResponse<byte[]> health = get(proxy, "/_proxy/health");
                assertEquals(200, health.statusCode());
                String json = new String(health.body(), StandardCharsets.UTF_8);
                assertTrue(json.contains("\"mode\":\"static-backends\""));
                assertTrue(json.contains("\"healthChecksEnabled\":false"));
                assertTrue(json.contains("\"loadBalancingStrategy\":\"" + strategy.configValue() + "\""));
                assertTrue(hits.isEmpty(), "Management must not send upstream requests or consume a selection");

                int requestCount = 400;
                List<String> selected = businessRequests(proxy, requestCount);
                List<String> period = period(strategy);
                for (int i = 0; i < selected.size(); i++) {
                    assertEquals(period.get(i % period.size()), selected.get(i),
                            "index=" + i + ", retried=" + logs.stream().filter(event -> event.attempts() > 1).toList());
                }
                int expectedA = strategy == LoadBalancingStrategy.ROUND_ROBIN ? 200 : 300;
                assertEquals(expectedA, Collections.frequency(selected, "a"));
                assertEquals(requestCount - expectedA, Collections.frequency(selected, "b"));
                assertEquals(requestCount, hits.size());
                assertTrue(hits.stream().allMatch(hit -> hit.target().equals("/" + hit.backend() + "/business")));
                List<AccessLogger.Event> businessEvents = new ArrayList<>();
                for (int i = 0; i < requestCount + 1; i++) {
                    AccessLogger.Event event = logs.poll(2, TimeUnit.SECONDS);
                    assertNotNull(event);
                    if (event.path().equals("/business")) {
                        assertEquals(1, event.attempts());
                        businessEvents.add(event);
                    }
                }
                assertEquals(requestCount, businessEvents.size());
                assertEquals(expectedA, businessEvents.stream().filter(event -> event.backend().equals("a")).count());
                assertEquals(requestCount - expectedA,
                        businessEvents.stream().filter(event -> event.backend().equals("b")).count());
                assertTrue(logs.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void respectsInjectedHealthTransitionsWithoutStartingAProbeScheduler(LoadBalancingStrategy strategy)
            throws Exception {
        try (Backend a = backend("a", 204); Backend b = backend("b", 204)) {
            ProxyConfig config = configuration(a, b, strategy);
            BackendRegistry registry = new BackendRegistry(config);
            try (ReverseProxyServer proxy = ReverseProxyServer.create(config, registry,
                    new JdkHttpTransport(config.connectTimeout()), logs::add)) {
                proxy.start();
                assertEquals(503, get(proxy, "/business").statusCode());
                assertEquals(503, get(proxy, "/_proxy/health").statusCode());
                assertTrue(hits.isEmpty(), "UNKNOWN backends must receive no traffic");

                registry.recordSuccess("a");
                registry.recordSuccess("b");
                assertEquals(period(strategy), businessRequests(proxy, 4));

                registry.recordFailure("a");
                registry.recordFailure("a");
                assertEquals(List.of("b", "b", "b", "b"), businessRequests(proxy, 4));
                registry.recordSuccess("a");
                assertEquals(period(strategy), businessRequests(proxy, 4));

                registry.recordFailure("a");
                registry.recordFailure("a");
                registry.recordFailure("b");
                registry.recordFailure("b");
                assertEquals(503, get(proxy, "/business").statusCode());
                assertEquals(503, get(proxy, "/_proxy/health").statusCode());
                assertEquals(12, hits.size());
                assertTrue(hits.stream().noneMatch(hit -> hit.target().contains("health")));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(LoadBalancingStrategy.class)
    void doesNotRetryPostWhenTheChosenBackendReturns503(LoadBalancingStrategy strategy)
            throws Exception {
        try (Backend a = backend("a", 503); Backend b = backend("b", 204)) {
            ProxyConfig config = configuration(a, b, strategy);
            assertEquals(2, config.maxAttempts());
            try (ReverseProxyServer proxy = start(config, new JdkHttpTransport(config.connectTimeout()), logs::add)) {
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url(proxy, "/business"))
                        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(503, response.statusCode());
                assertEquals("a unavailable", new String(response.body(), StandardCharsets.UTF_8));
                assertEquals(List.of(new Hit("a", "/a/business")), List.copyOf(hits));
                AccessLogger.Event event = logs.poll(2, TimeUnit.SECONDS);
                assertNotNull(event);
                assertEquals("a", event.backend());
                assertEquals(1, event.attempts());
                assertEquals(503, event.status());
            }
        }
    }

    private List<String> businessRequests(ReverseProxyServer proxy, int count) throws Exception {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HttpResponse<byte[]> response = get(proxy, "/business");
            assertEquals(204, response.statusCode());
            selected.add(response.headers().firstValue("X-Backend-Id").orElseThrow());
        }
        return selected;
    }

    private Backend backend(String id, int status) throws Exception {
        return new Backend(exchange -> {
            try (exchange) {
                // drainAmount=0 is JVM-wide. Read even an empty body to establish EOF and
                // keep this healthy fixture from silently closing a supposedly reusable connection.
                exchange.getRequestBody().readAllBytes();
                hits.add(new Hit(id, exchange.getRequestURI().toString()));
                exchange.getResponseHeaders().set("X-Backend-Id", id);
                byte[] body = (id + " unavailable").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
                if (status != 204) {
                    exchange.getResponseBody().write(body);
                }
            }
        });
    }

    private ProxyConfig configuration(Backend a, Backend b, LoadBalancingStrategy strategy) {
        return config(List.of(new BackendConfig("a", a.uri("/a"), 3, "/health"),
                new BackendConfig("b", b.uri("/b"), 1, "/health")), 1024, Duration.ofSeconds(2), strategy);
    }

    private HttpResponse<byte[]> get(ReverseProxyServer proxy, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(proxy, path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static List<String> period(LoadBalancingStrategy strategy) {
        return strategy == LoadBalancingStrategy.ROUND_ROBIN
                ? List.of("a", "b", "a", "b") : List.of("a", "a", "b", "a");
    }

    private record Hit(String backend, String target) {
    }
}
