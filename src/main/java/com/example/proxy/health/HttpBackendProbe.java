package com.example.proxy.health;

import com.example.proxy.config.BackendConfig;
import com.example.proxy.proxy.TargetUri;
import com.example.proxy.transport.UpstreamResponse;
import com.example.proxy.transport.UpstreamTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Objects;

/** Checks response headers only, then closes the upstream body without draining it. */
public final class HttpBackendProbe implements BackendProbe {
    private final UpstreamTransport transport;
    private final Duration timeout;

    public HttpBackendProbe(UpstreamTransport transport, Duration timeout) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("health timeout must be positive");
        }
    }

    @Override
    public boolean check(BackendConfig backend) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(TargetUri.resolve(backend.baseUri(), URI.create(backend.healthPath())))
                .timeout(timeout).header("User-Agent", "jdk-reverse-proxy-health").GET().build();
        try (UpstreamResponse response = transport.send(request)) {
            return response.statusCode() >= 200 && response.statusCode() <= 399;
        }
    }
}
