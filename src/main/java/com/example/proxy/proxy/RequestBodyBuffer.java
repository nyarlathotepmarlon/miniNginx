package com.example.proxy.proxy;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class RequestBodyBuffer {
    private RequestBodyBuffer() {
    }

    static byte[] read(HttpExchange exchange, long limit) throws IOException {
        String declaredLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declaredLength != null && Long.parseLong(declaredLength) > limit) {
            throw new BodyTooLargeException();
        }
        InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) Math.min(limit, 8192));
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(chunk, 0, (int) Math.min(chunk.length, limit - total + 1))) != -1) {
            total += read;
            if (total > limit) {
                throw new BodyTooLargeException();
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("Request body exceeds the configured limit");
        }
    }
}
