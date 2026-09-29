package com.example.proxy.support;

import com.example.proxy.balance.LoadBalancingStrategy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Post-package smoke check, independent of JUnit and run against the actual executable JAR. */
public final class JarSmokeCheck {
    private static final int NORMAL_REQUESTS = 400;
    private static final String PAYLOAD = "中文请求体 / binary-safe forwarding";

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: JarSmokeCheck <jar> [round-robin|weighted-round-robin]");
        }
        Path jar = Path.of(args[0]).toAbsolutePath();
        LoadBalancingStrategy strategy = args.length > 1
                ? LoadBalancingStrategy.fromConfigValue(args[1]) : LoadBalancingStrategy.ROUND_ROBIN;
        Path directory = ProxyTestSupport.testDirectory("jar-smoke-");
        Path config = directory.resolve("代理配置.properties");
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("access.log");
        Process process = null;
        HttpClient client = ProxyTestSupport.client();
        BackendStats primaryStats = new BackendStats("primary");
        BackendStats backupStats = new BackendStats("secondary");
        try (ProxyTestSupport.Backend backend = primaryStats.start(new InetSocketAddress("127.0.0.1", 0));
                ProxyTestSupport.Backend backup = backupStats.start(new InetSocketAddress("127.0.0.1", 0))) {
            int port;
            try (ServerSocket reservation = new ServerSocket()) {
                reservation.bind(new InetSocketAddress("127.0.0.1", 0));
                port = reservation.getLocalPort();
            }
            Files.writeString(config, "listen.port=" + port + "\nbackends=主节点,备节点\nbackend.主节点.url="
                    + backend.uri("/api") + "\nbackend.主节点.weight=3\nbackend.备节点.url=" + backup.uri("/api")
                    + "\nbackend.备节点.weight=1\nload-balancer.strategy=" + strategy.configValue()
                    + "\nproxy.worker-threads=4\nproxy.request-timeout-ms=750\nretry.max-attempts=2"
                    + "\nhealth.interval-ms=100\nhealth.timeout-ms=500\nhealth.failure-threshold=2\nhealth.success-threshold=2\n",
                    StandardCharsets.UTF_8);
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", jar.toString(), "--config", config.toString())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            URI base = URI.create("http://127.0.0.1:" + port);
            awaitHealth(client, base, 2, process, stderr, strategy);
            List<String> period = strategy == LoadBalancingStrategy.ROUND_ROBIN
                    ? List.of("primary", "secondary", "primary", "secondary")
                    : List.of("primary", "primary", "secondary", "primary");
            List<String> selected = checkRequests(client, base, period, NORMAL_REQUESTS);
            int primaryCount = Collections.frequency(selected, "primary");
            int expectedPrimary = strategy == LoadBalancingStrategy.ROUND_ROBIN
                    ? NORMAL_REQUESTS / 2 : NORMAL_REQUESTS * 3 / 4;
            if (primaryCount != expectedPrimary) {
                throw new AssertionError("Incorrect long-run distribution: primary=" + primaryCount);
            }
            InetSocketAddress primaryAddress = backend.address();
            backend.close();
            awaitHealth(client, base, 1, process, stderr, strategy);
            List<String> whileDown = checkRequests(client, base, List.of("secondary"), 4);
            List<String> afterRecovery;
            List<ExpectedLog> expectedLogs = new ArrayList<>();
            BackendStats restartedStats = new BackendStats("primary");
            try (ProxyTestSupport.Backend restarted = restartedStats.start(primaryAddress)) {
                awaitHealth(client, base, 2, process, stderr, strategy);
                afterRecovery = checkRequests(client, base, period, 4);
                try {
                    expectedLogs.addAll(checkTimeouts(client, base, restartedStats, backupStats));
                    awaitHealth(client, base, 2, process, stderr, strategy);
                    expectedLogs.add(checkOversizedRequest(base, restartedStats, backupStats));
                } finally {
                    restartedStats.releaseSlow.countDown();
                }
            }
            backup.close();
            awaitHealth(client, base, 0, process, stderr, strategy);
            HttpResponse<String> unavailable = client.send(HttpRequest.newBuilder(base.resolve("/business"))
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            if (unavailable.statusCode() != 503) {
                throw new AssertionError("All-unhealthy proxy did not return 503: " + unavailable);
            }
            expectedLogs.add(new ExpectedLog(requestId(unavailable), "GET", "/business", "-", 0, 503, "NO_BACKEND"));
            String startup = Files.readString(stdout);
            if (!startup.contains("健康检查与有限重试模式") || !startup.contains("主节点")
                    || !startup.contains("备节点") || !startup.contains("策略 " + strategy.configValue())) {
                throw new AssertionError("UTF-8 startup message missing: " + startup);
            }
            checkLogs(stderr, expectedLogs);
            String summary = "JAR_SMOKE_OK: " + strategy.configValue() + " first=" + selected.subList(0, 4)
                    + "; distribution=" + primaryCount + ":" + (NORMAL_REQUESTS - primaryCount)
                    + "; stopped=" + whileDown + "; restored=" + afterRecovery
                    + "; GET timeout->backup=200/attempts=2; POST timeout=504/attempts=1"
                    + "; oversized=413/attempts=0; all-down=503/attempts=0"
                    + "; UTF-8, raw URI, health transitions and final access logs verified";
            Files.writeString(directory.resolve("result.txt"), summary + System.lineSeparator(), StandardCharsets.UTF_8);
            System.out.println(summary);
            System.out.println("Artifacts: " + directory);
        } finally {
            primaryStats.releaseSlow.countDown();
            backupStats.releaseSlow.countDown();
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

    private static List<String> checkRequests(HttpClient client, URI base, List<String> period, int count) throws Exception {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/files/a%2Fb?q=a%2Bb"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(PAYLOAD))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String upstream = response.headers().firstValue("X-Smoke-Backend").orElse("");
            if (response.statusCode() != 201 || !response.body().equals(PAYLOAD)
                    || !upstream.equals(period.get(i % period.size()))) {
                throw new AssertionError("JAR forwarding/selection failed: " + response + ", backend=" + upstream);
            }
            selected.add(upstream);
        }
        return selected;
    }

    private static List<ExpectedLog> checkTimeouts(HttpClient client, URI base,
            BackendStats primary, BackendStats backup) throws Exception {
        HttpResponse<String> get = client.send(HttpRequest.newBuilder(base.resolve("/slow?token=smoke-secret-query"))
                .header("Authorization", "Bearer smoke-secret-authorization")
                .header("Cookie", "session=smoke-secret-cookie")
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (get.statusCode() != 200 || !get.body().equals("backup response")
                || primary.slowGet.get() != 1 || backup.slowGet.get() != 1) {
            throw new AssertionError("GET did not time out once on A and succeed once on B: " + get);
        }
        // After four full recovery selections and the GET retry, both strategies select A here.
        HttpResponse<String> post = client.send(HttpRequest.newBuilder(base.resolve("/slow"))
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(PAYLOAD))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (post.statusCode() != 504 || !post.body().contains("UPSTREAM_TIMEOUT")
                || primary.slowPost.get() != 1 || backup.slowPost.get() != 0) {
            throw new AssertionError("POST was not a single timeout on A: " + post);
        }
        return List.of(new ExpectedLog(requestId(get), "GET", "/slow", "备节点", 2, 200, "NONE"),
                new ExpectedLog(requestId(post), "POST", "/slow", "主节点", 1, 504, "UPSTREAM_TIMEOUT"));
    }

    private static ExpectedLog checkOversizedRequest(URI base, BackendStats primary, BackendStats backup)
            throws Exception {
        int before = primary.businessHits.get() + backup.businessHits.get();
        String response;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", base.getPort()), 2000);
            socket.setSoTimeout(5000);
            // The declared body is 10 MiB + 1: rejection must not wait for that upload to arrive.
            socket.getOutputStream().write(("POST /oversized HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 10485761\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        if (!response.startsWith("HTTP/1.1 413") || !response.contains("REQUEST_BODY_TOO_LARGE")
                || before != primary.businessHits.get() + backup.businessHits.get()) {
            throw new AssertionError("Oversized request was not rejected before forwarding: " + response);
        }
        String id = response.lines().filter(line -> line.regionMatches(true, 0, "X-Request-Id:", 0, 13))
                .map(line -> line.substring(13).strip()).findFirst().orElseThrow();
        return new ExpectedLog(id, "POST", "/oversized", "-", 0, 413, "REQUEST_BODY_TOO_LARGE");
    }

    private static String requestId(HttpResponse<?> response) {
        return response.headers().firstValue("X-Request-Id").orElseThrow();
    }

    private static void checkLogs(Path file, List<ExpectedLog> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        String logs;
        while (true) {
            logs = Files.readString(file, StandardCharsets.UTF_8);
            String current = logs;
            if (expected.stream().allMatch(event -> current.contains("requestId=\"" + event.requestId() + "\""))
                    || System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(20);
        }
        List<String> lines = logs.lines().toList();
        List<String> echoes = lines.stream().filter(line -> line.contains(" path=\"/files/a%2Fb\" ")).toList();
        if (echoes.size() != NORMAL_REQUESTS + 8 || echoes.stream().anyMatch(line ->
                !line.contains(" attempts=1 status=201 ") || !line.contains(" error=\"NONE\""))
                || logs.contains("q=a%2Bb") || logs.contains("smoke-secret") || logs.contains(PAYLOAD)
                || !logs.contains("event=backend_health") || !logs.contains("backend=\"主节点\"")
                || !logs.contains("from=UNHEALTHY to=HEALTHY")) {
            throw new AssertionError("Unexpected access or health logs; inspect " + file);
        }
        for (ExpectedLog event : expected) {
            List<String> matches = lines.stream()
                    .filter(line -> line.contains(" requestId=\"" + event.requestId() + "\" ")).toList();
            if (matches.size() != 1 || !matches.get(0).contains(" method=\"" + event.method() + "\" ")
                    || !matches.get(0).contains(" path=\"" + event.path() + "\" ")
                    || !matches.get(0).contains(" backend=\"" + event.backend() + "\" attempts=" + event.attempts()
                            + " status=" + event.status() + " ")
                    || !matches.get(0).contains(" error=\"" + event.error() + "\"")) {
                throw new AssertionError("Expected one final event for " + event + ": " + matches);
            }
        }
    }

    private record ExpectedLog(String requestId, String method, String path, String backend,
            int attempts, int status, String error) {
    }

    private static final class BackendStats {
        private final String id;
        private final AtomicInteger businessHits = new AtomicInteger();
        private final AtomicInteger slowGet = new AtomicInteger();
        private final AtomicInteger slowPost = new AtomicInteger();
        private final CountDownLatch releaseSlow = new CountDownLatch(1);

        private BackendStats(String id) {
            this.id = id;
        }

        private ProxyTestSupport.Backend start(InetSocketAddress address) throws Exception {
            return new ProxyTestSupport.Backend(address, exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getRawPath();
                    if (exchange.getRequestMethod().equals("GET") && path.equals("/api/health")) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    businessHits.incrementAndGet();
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    if (path.equals("/api/slow")) {
                        if (exchange.getRequestMethod().equals("GET")) {
                            slowGet.incrementAndGet();
                        } else if (exchange.getRequestMethod().equals("POST")) {
                            slowPost.incrementAndGet();
                        }
                        if (id.equals("primary")) {
                            try {
                                releaseSlow.await(15, TimeUnit.SECONDS);
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        } else {
                            byte[] reply = "backup response".getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, reply.length);
                            exchange.getResponseBody().write(reply);
                        }
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
