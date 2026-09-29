package com.example.proxy.proxy;

import com.example.proxy.backend.BackendPool;
import com.example.proxy.backend.BackendRegistry;
import com.example.proxy.balance.LoadBalancer;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.health.BackendProbe;
import com.example.proxy.health.HealthChecker;
import com.example.proxy.health.HttpBackendProbe;
import com.example.proxy.lifecycle.ExecutorShutdown;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.logging.HealthLogger;
import com.example.proxy.transport.JdkHttpTransport;
import com.example.proxy.transport.UpstreamTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReverseProxyServer implements AutoCloseable {
    static {
        // Reject oversized/unsupported requests without draining an arbitrarily slow upload on close.
        // Must precede the first HttpServer creation; integration tests also set it at JVM startup.
        System.setProperty("sun.net.httpserver.drainAmount", "0");
    }

    private static final AtomicInteger NEXT_SERVER_ID = new AtomicInteger();
    private HttpServer server;
    private final InetSocketAddress listenAddress;
    private final int backlog;
    private final ThreadPoolExecutor workers;
    private final RequestForwarder forwarder;
    private final HealthChecker healthChecker;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private boolean started;
    private boolean closed;

    private ReverseProxyServer(InetSocketAddress listenAddress, int backlog, ThreadPoolExecutor workers,
            RequestForwarder forwarder, HealthChecker healthChecker) {
        this.listenAddress = listenAddress;
        this.backlog = backlog;
        this.workers = workers;
        this.forwarder = forwarder;
        this.healthChecker = healthChecker;
    }

    public static ReverseProxyServer create(ProxyConfig config) throws IOException {
        UpstreamTransport transport = new JdkHttpTransport(config.connectTimeout());
        return createWithHealthChecks(config, new HttpBackendProbe(transport, config.healthTimeout()),
                transport, AccessLogger.console(), HealthLogger.console());
    }

    /** Production lifecycle with an injectable, interruptible probe for deterministic tests. */
    public static ReverseProxyServer createWithHealthChecks(ProxyConfig config, BackendProbe probe,
            UpstreamTransport transport, AccessLogger accessLogger, HealthLogger healthLogger) throws IOException {
        BackendRegistry registry = new BackendRegistry(config);
        HealthChecker checker = new HealthChecker(config, registry, probe, healthLogger);
        try {
            return create(config, registry, LoadBalancer.create(config.loadBalancingStrategy()),
                    transport, accessLogger, checker);
        } catch (IOException | RuntimeException failure) {
            checker.close();
            throw failure;
        }
    }

    /** Uses the configured strategy with an externally managed pool; no health scheduler is started. */
    public static ReverseProxyServer create(ProxyConfig config, BackendPool pool,
            UpstreamTransport transport, AccessLogger logger) throws IOException {
        return create(config, pool, LoadBalancer.create(config.loadBalancingStrategy()), transport, logger);
    }

    /** Dependency injection seam for local integration tests and subsequent implementation stages. */
    public static ReverseProxyServer create(ProxyConfig config, BackendPool pool, LoadBalancer selector,
            UpstreamTransport transport, AccessLogger logger) throws IOException {
        return create(config, pool, selector, transport, logger, null);
    }

    private static ReverseProxyServer create(ProxyConfig config, BackendPool pool, LoadBalancer selector,
            UpstreamTransport transport, AccessLogger logger, HealthChecker checker) throws IOException {
        RequestForwarder forwarder = new RequestForwarder(config, pool, selector, transport, logger, checker != null);
        int queueCapacity = Math.multiplyExact(config.workerThreads(), 2);
        String threadPrefix = "proxy-" + NEXT_SERVER_ID.incrementAndGet() + "-worker-";
        AtomicInteger nextThreadId = new AtomicInteger();
        ThreadPoolExecutor workers = new ThreadPoolExecutor(config.workerThreads(), config.workerThreads(),
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, threadPrefix + nextThreadId.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                // Rejection lets JDK HttpServer close the connection. Never run blocking I/O on its dispatcher.
                new ThreadPoolExecutor.AbortPolicy());
        // Do not allocate/bind an HttpServer before start(): JDK 17's not-yet-running dispatcher
        // cannot close its selector on stop(), leaving a bound channel pending deregistration on Windows.
        return new ReverseProxyServer(config.listenAddress(), queueCapacity, workers, forwarder, checker);
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("Proxy is closed");
        }
        if (!started) {
            try {
                server = HttpServer.create(listenAddress, backlog);
                server.setExecutor(workers);
                server.createContext("/", forwarder);
                server.start();
                started = true;
                if (healthChecker != null) {
                    healthChecker.start();
                }
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }
    }

    /** Before start, returns the configured address (possibly port 0); afterward, the bound address. */
    public synchronized InetSocketAddress address() {
        return server == null ? listenAddress : server.getAddress();
    }

    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            try {
                // Stop accepting, allowing in-flight exchanges a short grace period.
                if (server != null) {
                    server.stop(started ? 1 : 0);
                }
            } finally {
                if (healthChecker != null) {
                    healthChecker.close();
                }
            }
        } finally {
            try {
                forwarder.close();
            } finally {
                try {
                    ExecutorShutdown.close(workers);
                } finally {
                    terminated.countDown();
                }
            }
        }
    }
}
