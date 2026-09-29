package com.example.proxy.transport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A closeable upstream response independent of the concrete JDK HTTP client implementation. */
public record UpstreamResponse(
        int statusCode, Map<String, List<String>> headers, InputStream body)
        implements AutoCloseable {

    public UpstreamResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 100 and 599");
        }
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(body, "body must not be null");

        LinkedHashMap<String, List<String>> headerCopy = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            Objects.requireNonNull(name, "header name must not be null");
            Objects.requireNonNull(values, "header values must not be null");
            headerCopy.put(name, List.copyOf(values));
        });
        headers = Collections.unmodifiableMap(headerCopy);
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
