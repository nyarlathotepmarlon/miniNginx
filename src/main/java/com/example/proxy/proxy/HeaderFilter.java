package com.example.proxy.proxy;

import com.sun.net.httpserver.Headers;
import java.net.http.HttpRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Connection tokens are case-insensitive and apply independently in each direction. */
public final class HeaderFilter {
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-connection", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");
    private static final Set<String> REQUEST_MANAGED = Set.of(
            "host", "content-length", "expect", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto", "x-request-id");

    private HeaderFilter() {
    }

    public static void copyRequestHeaders(Headers incoming, HttpRequest.Builder target,
            String clientAddress, String requestId) {
        Set<String> excluded = excluded(incoming);
        incoming.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!excluded.contains(lower) && !REQUEST_MANAGED.contains(lower)) {
                values.forEach(value -> target.header(name, value));
            }
        });
        String forwardedFor = excluded.contains("x-forwarded-for")
                ? null : String.join(", ", incoming.getOrDefault("X-Forwarded-For", List.of()));
        target.header("X-Forwarded-For", forwardedFor == null || forwardedFor.isBlank()
                ? clientAddress : forwardedFor + ", " + clientAddress);
        String originalHost = incoming.getFirst("Host");
        if (originalHost != null) {
            target.header("X-Forwarded-Host", originalHost);
        }
        target.header("X-Forwarded-Proto", "http");
        target.header("X-Request-Id", requestId);
        target.header("Via", "1.1 jdk-reverse-proxy");
    }

    public static void copyResponseHeaders(Map<String, List<String>> incoming, Headers target) {
        Set<String> excluded = excluded(incoming);
        incoming.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!excluded.contains(lower) && !lower.equals("content-length")
                    && !lower.equals("x-request-id")) {
                values.forEach(value -> target.add(name, value));
            }
        });
        target.add("Via", "1.1 jdk-reverse-proxy");
    }

    static boolean requestsConnectionClose(Headers incoming) {
        return excluded(incoming).contains("close");
    }

    private static Set<String> excluded(Map<String, List<String>> headers) {
        Set<String> excluded = new HashSet<>(HOP_BY_HOP);
        headers.forEach((name, values) -> {
            if (name.equalsIgnoreCase("Connection")) {
                values.forEach(value -> {
                    for (String token : value.split(",")) {
                        excluded.add(token.strip().toLowerCase(Locale.ROOT));
                    }
                });
            }
        });
        return excluded;
    }
}
