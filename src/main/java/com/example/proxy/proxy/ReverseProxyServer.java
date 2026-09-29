package com.example.proxy.proxy;

import com.example.proxy.backend.BackendPool;
import com.example.proxy.backend.StaticBackendPool;
import com.example.proxy.balance.LoadBalancer;
import com.example.proxy.balance.SingleBackendSelector;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.logging.AccessLogger;
import com.example.proxy.transport.JdkHttpTransport;
import com.example.proxy.transport.UpstreamTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
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
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final RequestForwarder forwarder;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private boolean started;
    private boolean closed;

    private ReverseProxyServer(HttpServer server, ThreadPoolExecutor workers, RequestForwarder forwarder) {
        this.server = server;
        this.workers = workers;
        this.forwarder = forwarder;
    }

    public static ReverseProxyServer create(ProxyConfig config) throws IOException {
        return create(config, new StaticBackendPool(List.of(config.backends().get(0))),
                new SingleBackendSelector(), new JdkHttpTransport(config.connectTimeout()), AccessLogger.console());
    }

    /** Dependency injection seam for local integration tests and subsequent implementation stages. */
    public static ReverseProxyServer create(ProxyConfig config, BackendPool pool, LoadBalancer selector,
            UpstreamTransport transport, AccessLogger logger) throws IOException {
        RequestForwarder forwarder = new RequestForwarder(config, pool, selector, transport, logger);
        int queueCapacity = Math.multiplyExact(config.workerThreads(), 2);
        String threadPrefix = "proxy-" + NEXT_SERVER_ID.incrementAndGet() + "-worker-";
        AtomicInteger nextThreadId = new AtomicInteger();
        ThreadPoolExecutor workers = new ThreadPoolExecutor(config.workerThreads(), config.workerThreads(),
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), task ->
                        new Thread(task, threadPrefix + nextThreadId.incrementAndGet()),
                // Rejection lets JDK HttpServer close the connection. Never run blocking I/O on its dispatcher.
                new ThreadPoolExecutor.AbortPolicy());
        HttpServer server = null;
        try {
            server = HttpServer.create(config.listenAddress(), queueCapacity);
            server.setExecutor(workers);
            server.createContext("/", forwarder);
            return new ReverseProxyServer(server, workers, forwarder);
        } catch (IOException | RuntimeException failure) {
            if (server != null) {
                server.stop(0);
            }
            workers.shutdownNow();
            throw failure;
        }
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Proxy is closed");
        }
        if (!started) {
            server.start();
            started = true;
        }
    }

    public InetSocketAddress address() {
        return server.getAddress();
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
            server.stop(0);
            forwarder.close();
        } finally {
            workers.shutdownNow();
            try {
                workers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                terminated.countDown();
            }
        }
    }
}
