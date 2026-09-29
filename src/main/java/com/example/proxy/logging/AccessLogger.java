package com.example.proxy.logging;

import static com.example.proxy.logging.LogSupport.quote;

import java.time.Instant;
import java.util.logging.Logger;

/** A single final event per handled request; never includes request/response headers or bodies. */
@FunctionalInterface
public interface AccessLogger {
    void log(Event event);

    record Event(Instant timestamp, String requestId, String client, String method, String path,
            String backend, int attempts, int status, long responseBytes, long durationMs, String error) {
        public String format() {
            return "timestamp=" + timestamp + " requestId=" + quote(requestId)
                    + " client=" + quote(client) + " method=" + quote(method) + " path=" + quote(path)
                    + " backend=" + quote(backend) + " attempts=" + attempts + " status=" + status
                    + " responseBytes=" + responseBytes + " durationMs=" + durationMs
                    + " error=" + quote(error);
        }
    }

    static AccessLogger console() {
        return Console.INSTANCE;
    }

    final class Console {
        private static final AccessLogger INSTANCE = create();

        private Console() {
        }

        private static AccessLogger create() {
            Logger logger = LogSupport.console("com.example.proxy.access");
            return event -> logger.info(event.format());
        }
    }
}
