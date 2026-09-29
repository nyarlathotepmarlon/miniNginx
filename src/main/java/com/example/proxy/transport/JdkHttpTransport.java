package com.example.proxy.transport;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** One HTTP/1.1 exchange. The caller owns and must close the returned response body. */
public final class JdkHttpTransport implements UpstreamTransport {
    private final HttpClient client;

    /** Must run before the first HttpClient exchange in this JVM (JDK caches these properties). */
    public static void configureRuntime() {
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        // disableRetryConnect alone does not disable the stale-connection retry path in JDK 17.
        System.setProperty("jdk.httpclient.redirects.retrylimit", "1");
    }

    public JdkHttpTransport(Duration connectTimeout) {
        configureRuntime();
        client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                // Connect directly to configured backends, independent of machine proxy settings.
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                        // The failure is returned to the proxy via send().
                    }
                })
                .build();
    }

    @Override
    public UpstreamResponse send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<java.io.InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            return new UpstreamResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (IllegalArgumentException failure) {
            try {
                response.body().close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new IOException("Invalid upstream response", failure);
        }
    }
}
