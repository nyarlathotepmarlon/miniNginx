package com.example.proxy.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccessLoggerTest {
    @Test
    void quotesAndEscapesUntrustedFieldsOnASingleLine() {
        AccessLogger.Event event = new AccessLogger.Event(Instant.EPOCH, "id", "127.0.0.1", "GET",
                "/path", "中文节点\nstatus=200\"\\", 1, 502, 3, 4, "UPSTREAM_IO_FAILURE");
        String formatted = event.format();
        assertFalse(formatted.contains("\n"));
        assertTrue(formatted.contains("中文节点\\u000astatus=200\\\"\\\\"));
        assertTrue(formatted.contains("status=502"));
    }
}
