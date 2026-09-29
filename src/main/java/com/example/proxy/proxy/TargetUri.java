package com.example.proxy.proxy;

import java.net.URI;

/** Joins raw URI components without decoding, re-encoding '%' or normalizing path segments. */
public final class TargetUri {
    private TargetUri() {
    }

    public static String rawPath(URI requestTarget) {
        if (requestTarget.isAbsolute() || requestTarget.getRawFragment() != null
                || !requestTarget.toString().startsWith("/")) {
            throw new IllegalArgumentException("Only origin-form request targets are supported");
        }
        String path = requestTarget.getRawPath();
        // URI parses an origin-form target such as //files/a as a network-path reference.
        // Reconstruct that prefix so a client-supplied double slash remains a path, not a host.
        if (requestTarget.getRawAuthority() != null) {
            path = "//" + requestTarget.getRawAuthority() + (path == null ? "" : path);
        }
        return path;
    }

    public static URI resolve(URI backend, URI requestTarget) {
        String path = rawPath(requestTarget);
        String prefix = backend.getRawPath();
        if (prefix == null) {
            prefix = "";
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String query = requestTarget.getRawQuery();
        return URI.create(backend.getScheme() + "://" + backend.getRawAuthority()
                + prefix + path + (query == null ? "" : "?" + query));
    }
}
