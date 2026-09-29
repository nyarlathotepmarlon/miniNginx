package com.example.proxy.transport;

import java.io.IOException;
import java.net.http.HttpRequest;

/** Sends one proxy-level upstream attempt. Implementations must not perform business retries. */
@FunctionalInterface
public interface UpstreamTransport {
    UpstreamResponse send(HttpRequest request) throws IOException, InterruptedException;
}
