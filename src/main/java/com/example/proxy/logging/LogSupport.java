package com.example.proxy.logging;

import java.nio.charset.StandardCharsets;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class LogSupport {
    private LogSupport() {
    }

    static String quote(String value) {
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

    // Called once by each console logger's initialization holder.
    static Logger console(String name) {
        Logger logger = Logger.getLogger(name);
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
        return logger;
    }
}
