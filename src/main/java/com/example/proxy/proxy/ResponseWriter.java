package com.example.proxy.proxy;

import com.example.proxy.transport.UpstreamResponse;
import com.sun.net.httpserver.HttpExchange;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Owns response commitment, framing and byte accounting for one downstream exchange. */
final class ResponseWriter {
    private final HttpExchange exchange;
    private final String requestId;
    private final AbortableOutputStream output;
    private volatile boolean committed;
    private int status;
    private long bytes;

    ResponseWriter(HttpExchange exchange, String requestId) {
        this.exchange = exchange;
        this.requestId = requestId;
        output = new AbortableOutputStream(exchange.getResponseBody());
        exchange.setStreams(null, output);
    }

    void forward(UpstreamResponse response) throws IOException {
        if (response.statusCode() < 200) {
            throw new IOException("Protocol upgrades and informational final responses are unsupported");
        }
        HeaderFilter.copyResponseHeaders(response.headers(), exchange.getResponseHeaders());
        boolean noBody = exchange.getRequestMethod().equals("HEAD")
                || response.statusCode() == 204 || response.statusCode() == 304
                || response.statusCode() == 205;
        start(response.statusCode(), noBody ? -1 : 0);
        if (!noBody) {
            byte[] buffer = new byte[8192];
            while (true) {
                final int read;
                try {
                    read = response.body().read(buffer);
                } catch (IOException failure) {
                    throw new StreamFailure("UPSTREAM_STREAM_FAILURE", failure);
                }
                if (read == -1) {
                    break;
                }
                write(buffer, read);
            }
        }
        finish();
    }

    void json(int responseStatus, String json) throws IOException {
        exchange.getResponseHeaders().clear();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (responseStatus >= 400) {
            // An oversized/unread request must not be reused as the next request on this socket.
            exchange.getResponseHeaders().set("Connection", "close");
        }
        if (responseStatus == 405) {
            exchange.getResponseHeaders().set("Allow", "GET");
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        boolean head = exchange.getRequestMethod().equals("HEAD");
        start(responseStatus, head ? -1 : body.length);
        if (!head) {
            write(body, body.length);
        }
        finish();
    }

    private void start(int responseStatus, long length) throws IOException {
        status = responseStatus;
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        if (HeaderFilter.requestsConnectionClose(exchange.getRequestHeaders())) {
            exchange.getResponseHeaders().set("Connection", "close");
        }
        // Mark before writing: sendResponseHeaders can fail after partially writing headers.
        committed = true;
        try {
            exchange.sendResponseHeaders(responseStatus, length);
        } catch (IOException failure) {
            throw new StreamFailure("CLIENT_STREAM_FAILURE", failure);
        }
    }

    private void write(byte[] buffer, int length) throws IOException {
        try {
            output.write(buffer, 0, length);
            output.flush();
            bytes += length;
        } catch (IOException failure) {
            throw new StreamFailure("CLIENT_STREAM_FAILURE", failure);
        }
    }

    private void finish() throws IOException {
        try {
            output.close();
        } catch (IOException failure) {
            throw new StreamFailure("CLIENT_STREAM_FAILURE", failure);
        }
    }

    void abort() {
        output.aborted = true;
        exchange.close();
    }

    boolean committed() {
        return committed;
    }

    int status() {
        return status;
    }

    long bytes() {
        return bytes;
    }

    static final class StreamFailure extends IOException {
        private final String category;

        StreamFailure(String category, IOException cause) {
            super(category, cause);
            this.category = category;
        }

        String category() {
            return category;
        }
    }

    private static final class AbortableOutputStream extends FilterOutputStream {
        private volatile boolean aborted;

        private AbortableOutputStream(OutputStream delegate) {
            super(delegate);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
        }

        @Override
        public void close() throws IOException {
            if (aborted) {
                // JDK HttpServer closes the socket if an installed output wrapper fails to close.
                // Do not let ChunkedOutputStream emit a successful terminal chunk for a partial body.
                throw new IOException("Aborted downstream response");
            }
            out.close();
        }
    }
}
