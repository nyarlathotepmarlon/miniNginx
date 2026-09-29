package com.example.proxy.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/** In-memory exchange for deterministic forwarding tests; real socket framing is tested separately. */
public final class MemoryExchange extends HttpExchange {
    private final String method;
    private final URI target;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();
    private InputStream input;
    private OutputStream output;
    public final ByteArrayOutputStream response = new ByteArrayOutputStream();
    public int status;
    public int commits;
    public long responseLength;
    public boolean closed;

    public MemoryExchange(String method, String target, byte[] body) {
        this.method = method;
        this.target = URI.create(target);
        input = new ByteArrayInputStream(body);
        output = response;
        requestHeaders.set("Host", "proxy.example");
        requestHeaders.set("Content-Length", Integer.toString(body.length));
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return target;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
        closed = true;
        try {
            input.close();
            output.close();
        } catch (IOException ignored) {
            // Mirrors HttpExchange.close(): a stream failure must not escape cleanup.
        }
    }

    @Override
    public InputStream getRequestBody() {
        return input;
    }

    @Override
    public OutputStream getResponseBody() {
        return output;
    }

    @Override
    public void sendResponseHeaders(int code, long length) {
        status = code;
        responseLength = length;
        commits++;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 12345);
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 8080);
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    @Override
    public void setStreams(InputStream input, OutputStream output) {
        if (input != null) {
            this.input = input;
        }
        if (output != null) {
            this.output = output;
        }
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }

    @Override
    public int getResponseCode() {
        return status;
    }
}
