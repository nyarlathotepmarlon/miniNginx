package com.example.proxy.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/** Runs in an isolated JVM to verify runtime settings before JDK caches retry properties. */
public final class NoRetryProbe {
    public static void main(String[] args) throws Exception {
        JdkHttpTransport transport = new JdkHttpTransport(Duration.ofSeconds(1));
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(2))
                .method(args[1], HttpRequest.BodyPublishers.noBody()).build();
        try (UpstreamResponse response = transport.send(request)) {
            throw new AssertionError("Fault server unexpectedly responded: " + response.statusCode());
        } catch (IOException expected) {
            System.out.println("EXPECTED_IO_FAILURE");
        }
    }
}
