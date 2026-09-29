package com.example.proxy.support;

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
import java.util.concurrent.TimeUnit;

/** Post-package smoke check, independent of JUnit and run against the actual executable JAR. */
public final class JarSmokeCheck {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path directory = ProxyTestSupport.testDirectory("jar-smoke-");
        Path config = directory.resolve("代理配置.properties");
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("access.log");
        Process process = null;
        HttpClient client = ProxyTestSupport.client();
        try (ProxyTestSupport.Backend backend = new ProxyTestSupport.Backend(exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (!exchange.getRequestURI().toString().equals("/api/files/a%2Fb?q=a%2Bb")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                exchange.getResponseHeaders().set("X-Smoke-Backend", "primary");
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            }
        })) {
            int port;
            try (ServerSocket reservation = new ServerSocket()) {
                reservation.bind(new InetSocketAddress("127.0.0.1", 0));
                port = reservation.getLocalPort();
            }
            Files.writeString(config, "listen.port=" + port + "\nbackends=主节点\nbackend.主节点.url="
                    + backend.uri("/api") + "\n", StandardCharsets.UTF_8);
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", jar.toString(), "--config", config.toString())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            URI base = URI.create("http://127.0.0.1:" + port);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            boolean ready = false;
            while (System.nanoTime() < deadline && process.isAlive()) {
                try {
                    HttpResponse<String> health = client.send(HttpRequest.newBuilder(base.resolve("/_proxy/health"))
                            .timeout(Duration.ofMillis(300)).build(), HttpResponse.BodyHandlers.ofString());
                    ready = health.statusCode() == 200 && health.body().contains("\"healthChecksEnabled\":false");
                    if (ready) {
                        break;
                    }
                } catch (java.io.IOException expectedDuringStartup) {
                    // Wait until the independently launched JAR has bound its port.
                }
                Thread.sleep(50);
            }
            if (!ready) {
                throw new AssertionError("JAR not ready: " + Files.readString(stderr));
            }
            String payload = "中文请求体 / binary-safe forwarding";
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create(base + "/files/a%2Fb?q=a%2Bb"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 201 || !response.body().equals(payload)
                    || !response.headers().firstValue("X-Smoke-Backend").orElse("").equals("primary")) {
                throw new AssertionError("JAR forwarding failed: " + response);
            }
            String startup = Files.readString(stdout);
            if (!startup.contains("阶段 2 单后端模式") || !startup.contains("主节点")) {
                throw new AssertionError("UTF-8 startup message missing: " + startup);
            }
            long logDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.readString(stderr).contains("status=201") && System.nanoTime() < logDeadline) {
                Thread.sleep(20);
            }
            String logs = Files.readString(stderr);
            if (!logs.contains("status=201") || !logs.contains("attempts=1") || logs.contains("q=a%2Bb")) {
                throw new AssertionError("Unexpected access log: " + logs);
            }
            System.out.println("JAR_SMOKE_OK: POST 201, raw URI, UTF-8 body/startup, health and one-attempt access log");
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
}
