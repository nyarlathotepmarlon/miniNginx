package com.example.proxy.support;

import com.example.proxy.backend.StaticBackendPool;
import com.example.proxy.balance.LoadBalancingStrategy;
import com.example.proxy.config.BackendConfig;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.proxy.ReverseProxyServer;
import com.example.proxy.transport.JdkHttpTransport;
import com.example.proxy.transport.UpstreamTransport;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ProxyTestSupport {
    private ProxyTestSupport() {
    }

    public static HttpClient client() {
        JdkHttpTransport.configureRuntime();
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
    }

    public static ProxyConfig config(URI upstream) {
        return config(upstream, 10L * 1024 * 1024, Duration.ofSeconds(2));
    }

    public static ProxyConfig config(URI upstream, long bodyLimit, Duration timeout) {
        return config(List.of(new BackendConfig("primary", upstream, 1, "/health")), bodyLimit, timeout);
    }

    public static ProxyConfig config(List<BackendConfig> backends, long bodyLimit, Duration timeout) {
        return config(backends, bodyLimit, timeout, LoadBalancingStrategy.ROUND_ROBIN);
    }

    public static ProxyConfig config(List<BackendConfig> backends, long bodyLimit, Duration timeout,
            LoadBalancingStrategy strategy) {
        return new ProxyConfig(new InetSocketAddress("127.0.0.1", 0), 4,
                strategy, backends,
                Duration.ofSeconds(5), Duration.ofSeconds(1), 2, 1,
                Duration.ofSeconds(1), timeout, bodyLimit, 2, Set.of(502, 503, 504), "/_proxy");
    }

    public static ProxyConfig healthConfig(ProxyConfig base, Duration interval, Duration timeout,
            int failureThreshold, int successThreshold) {
        return new ProxyConfig(base.listenAddress(), base.workerThreads(), base.loadBalancingStrategy(),
                base.backends(), interval, timeout, failureThreshold, successThreshold,
                base.connectTimeout(), base.requestTimeout(), base.maxRequestBodyBytes(), base.maxAttempts(),
                base.retryStatusCodes(), base.managementPathPrefix());
    }

    public static ProxyConfig retryConfig(ProxyConfig base, int maxAttempts, Set<Integer> statusCodes) {
        return new ProxyConfig(base.listenAddress(), base.workerThreads(), base.loadBalancingStrategy(),
                base.backends(), base.healthInterval(), base.healthTimeout(), base.healthFailureThreshold(),
                base.healthSuccessThreshold(), base.connectTimeout(), base.requestTimeout(),
                base.maxRequestBodyBytes(), maxAttempts, statusCodes, base.managementPathPrefix());
    }

    public static void awaitHealthy(HttpClient client, ReverseProxyServer proxy, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String last = "";
        do {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(url(proxy, "/_proxy/health"))
                    .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.ofString());
            last = response.body();
            if (response.statusCode() == (count > 0 ? 200 : 503)
                    && last.contains("\"healthyBackends\":" + count)) {
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Health count did not become " + count + ": " + last);
    }

    public static ReverseProxyServer start(ProxyConfig config, UpstreamTransport transport, AccessLogger logger)
            throws IOException {
        ReverseProxyServer proxy = ReverseProxyServer.create(config,
                new StaticBackendPool(config.backends()), transport, logger);
        proxy.start();
        return proxy;
    }

    public static URI url(ReverseProxyServer proxy, String path) {
        return URI.create("http://127.0.0.1:" + proxy.address().getPort() + path);
    }

    public static String rawRequest(ReverseProxyServer proxy, String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(proxy.address(), 2000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    public static Path testDirectory(String prefix) throws IOException {
        Path parent = Path.of("target", "test-data");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, prefix).toAbsolutePath();
    }

    public static final class Backend implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        public Backend(HttpHandler handler) throws IOException {
            this(new InetSocketAddress("127.0.0.1", 0), handler);
        }

        public Backend(InetSocketAddress address, HttpHandler handler) throws IOException {
            server = HttpServer.create(address, 16);
            server.setExecutor(executor);
            server.createContext("/", handler);
            server.start();
        }

        public URI uri(String basePath) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + basePath);
        }

        public InetSocketAddress address() {
            return server.getAddress();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test backend did not stop");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }
}
