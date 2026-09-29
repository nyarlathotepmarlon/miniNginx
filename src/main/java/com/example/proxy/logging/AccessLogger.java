package com.example.proxy.logging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
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

        private static String quote(String value) {
            StringBuilder escaped = new StringBuilder("\"");
            for (char character : value.toCharArray()) {
                if (character == '\\' || character == '"') {
                    escaped.append('\\').append(character);
                } else if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
                    escaped.append(String.format("\\u%04x", (int) character));
                } else {
                    escaped.append(character);
                }
            }
            return escaped.append('"').toString();
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
            Logger logger = Logger.getLogger("com.example.proxy.access");
            logger.setUseParentHandlers(false);
            ConsoleHandler handler = new ConsoleHandler();
            try {
                handler.setEncoding(StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new AssertionError(impossible);
            }
            handler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return record.getMessage() + System.lineSeparator();
                }
            });
            logger.addHandler(handler);
            return event -> logger.info(event.format());
        }
    }
}
