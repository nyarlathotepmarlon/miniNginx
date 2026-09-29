package com.example.proxy.support;

import com.example.proxy.balance.LoadBalancingStrategy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Post-package smoke check, independent of JUnit and run against the actual executable JAR. */
public final class JarSmokeCheck {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        LoadBalancingStrategy strategy = args.length > 1
                ? LoadBalancingStrategy.fromConfigValue(args[1]) : LoadBalancingStrategy.ROUND_ROBIN;
        Path directory = ProxyTestSupport.testDirectory("jar-smoke-");
        Path config = directory.resolve("代理配置.properties");
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("access.log");
        Process process = null;
        HttpClient client = ProxyTestSupport.client();
        try (ProxyTestSupport.Backend backend = echoBackend("primary");
                ProxyTestSupport.Backend backup = echoBackend("secondary")) {
            int port;
            try (ServerSocket reservation = new ServerSocket()) {
                reservation.bind(new InetSocketAddress("127.0.0.1", 0));
                port = reservation.getLocalPort();
            }
            Files.writeString(config, "listen.port=" + port + "\nbackends=主节点,备节点\nbackend.主节点.url="
                    + backend.uri("/api") + "\nbackend.主节点.weight=3\nbackend.备节点.url=" + backup.uri("/api")
                    + "\nbackend.备节点.weight=1\nload-balancer.strategy=" + strategy.configValue()
                    + "\nhealth.interval-ms=50\nhealth.timeout-ms=250\nhealth.failure-threshold=2\nhealth.success-threshold=2\n",
                    StandardCharsets.UTF_8);
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", jar.toString(), "--config", config.toString())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            URI base = URI.create("http://127.0.0.1:" + port);
            awaitHealth(client, base, 2, process, stderr, strategy);
            List<String> period = strategy == LoadBalancingStrategy.ROUND_ROBIN
                    ? List.of("primary", "secondary", "primary", "secondary")
                    : List.of("primary", "primary", "secondary", "primary");
            List<String> selected = checkRequests(client, base, period, 8);
            InetSocketAddress primaryAddress = backend.address();
            backend.close();
            awaitHealth(client, base, 1, process, stderr, strategy);
            List<String> whileDown = checkRequests(client, base, List.of("secondary"), 4);
            List<String> afterRecovery;
            try (ProxyTestSupport.Backend restarted = echoBackend("primary", primaryAddress)) {
                awaitHealth(client, base, 2, process, stderr, strategy);
                afterRecovery = checkRequests(client, base, period, 4);
            }
            backup.close();
            awaitHealth(client, base, 0, process, stderr, strategy);
            HttpResponse<String> unavailable = client.send(HttpRequest.newBuilder(base.resolve("/business"))
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            if (unavailable.statusCode() != 503) {
                throw new AssertionError("All-unhealthy proxy did not return 503: " + unavailable);
            }
            String startup = Files.readString(stdout);
            if (!startup.contains("阶段 5 健康检查与有限重试模式") || !startup.contains("主节点")
                    || !startup.contains("备节点") || !startup.contains("策略 " + strategy.configValue())) {
                throw new AssertionError("UTF-8 startup message missing: " + startup);
            }
            long logDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (Files.readString(stderr).lines().filter(line -> line.contains("status=201")).count() < 16
                    && System.nanoTime() < logDeadline) {
                Thread.sleep(20);
            }
            String logs = Files.readString(stderr);
            List<String> businessLogs = logs.lines().filter(line -> line.contains("status=201")).toList();
            if (businessLogs.size() != 16 || businessLogs.stream().anyMatch(line -> !line.contains("attempts=1"))
                    || logs.contains("q=a%2Bb") || !logs.contains("event=backend_health")
                    || !logs.contains("backend=\"主节点\"") || !logs.contains("from=UNHEALTHY to=HEALTHY")) {
                throw new AssertionError("Unexpected access log: " + logs);
            }
            System.out.println("JAR_SMOKE_OK: " + strategy.configValue() + " " + selected
                    + "; stopped=" + whileDown + "; restored=" + afterRecovery
                    + "; all-down=503; POST 201, raw URI, UTF-8, health transitions and one-attempt access log");
        } finally {
            if (process != null) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Smoke proxy process did not terminate");
                    }
                }
            }
        }
    }

    private static ProxyTestSupport.Backend echoBackend(String id) throws Exception {
        return echoBackend(id, new InetSocketAddress("127.0.0.1", 0));
    }

    private static ProxyTestSupport.Backend echoBackend(String id, InetSocketAddress address) throws Exception {
        return new ProxyTestSupport.Backend(address, exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getRawPath().equals("/api/health")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!exchange.getRequestMethod().equals("POST")
                        || !exchange.getRequestURI().toString().equals("/api/files/a%2Fb?q=a%2Bb")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                exchange.getResponseHeaders().set("X-Smoke-Backend", id);
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            }
        });
    }

    private static List<String> checkRequests(HttpClient client, URI base, List<String> period, int count) throws Exception {
        String payload = "中文请求体 / binary-safe forwarding";
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/files/a%2Fb?q=a%2Bb"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String upstream = response.headers().firstValue("X-Smoke-Backend").orElse("");
            if (response.statusCode() != 201 || !response.body().equals(payload)
                    || !upstream.equals(period.get(i % period.size()))) {
                throw new AssertionError("JAR forwarding/selection failed: " + response + ", backend=" + upstream);
            }
            selected.add(upstream);
        }
        return selected;
    }

    private static void awaitHealth(HttpClient client, URI base, int count, Process process, Path stderr,
            LoadBalancingStrategy strategy) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                HttpResponse<String> health = client.send(HttpRequest.newBuilder(base.resolve("/_proxy/health"))
                        .timeout(Duration.ofMillis(300)).build(), HttpResponse.BodyHandlers.ofString());
                if (health.statusCode() == (count > 0 ? 200 : 503)
                        && health.body().contains("\"healthChecksEnabled\":true")
                        && health.body().contains("\"healthyBackends\":" + count)
                        && health.body().contains("\"loadBalancingStrategy\":\"" + strategy.configValue() + "\"")) {
                    return;
                }
            } catch (java.io.IOException expectedDuringStartup) {
                // The independently launched process may not have bound its port yet.
            }
            Thread.sleep(20);
        }
        throw new AssertionError("JAR healthy count did not become " + count + ": " + Files.readString(stderr));
    }
}
