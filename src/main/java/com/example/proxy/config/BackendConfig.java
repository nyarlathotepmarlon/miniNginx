package com.example.proxy.config;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record BackendConfig(String id, URI baseUri, int weight, String healthPath) {
    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");

    public BackendConfig {
        Objects.requireNonNull(id, "backend id must not be null");
        Objects.requireNonNull(baseUri, "backend baseUri must not be null");
        Objects.requireNonNull(healthPath, "backend healthPath must not be null");

        if (id.isBlank() || !id.equals(id.strip())) {
            throw new IllegalArgumentException("backend id must be non-blank and trimmed");
        }
        if (id.indexOf(',') >= 0 || id.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("backend id must not contain commas or whitespace: " + id);
        }

        String scheme = baseUri.getScheme();
        if (scheme == null || !SUPPORTED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("backend " + id + " URL must use http or https");
        }
        if (!baseUri.isAbsolute() || baseUri.getHost() == null) {
            throw new IllegalArgumentException("backend " + id + " URL must contain a host");
        }
        if (baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "backend " + id + " URL must not contain a query or fragment");
        }
        if (baseUri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("backend " + id + " URL must not contain user credentials");
        }
        if (baseUri.getPort() == 0 || baseUri.getPort() > 65_535) {
            throw new IllegalArgumentException("backend " + id + " port must be between 1 and 65535");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("backend " + id + " weight must be positive");
        }

        validateHealthPath(id, healthPath);
    }

    private static void validateHealthPath(String id, String healthPath) {
        if (healthPath.isBlank() || !healthPath.startsWith("/")) {
            throw new IllegalArgumentException("backend " + id + " health path must start with /");
        }
        final URI uri;
        try {
            uri = URI.create(healthPath);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "backend " + id + " health path is not a valid URI path", exception);
        }
        if (uri.isAbsolute()
                || uri.getRawAuthority() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !healthPath.equals(uri.getRawPath())) {
            throw new IllegalArgumentException(
                    "backend " + id + " health path must not contain an authority, query, or fragment");
        }
    }
}
