package com.example.proxy.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class UpstreamResponseTest {
    @Test
    void defensivelyCopiesHeadersAndClosesTheBody() throws Exception {
        List<String> values = new ArrayList<>(List.of("one"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Test", values);
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        UpstreamResponse response = new UpstreamResponse(200, headers, body);
        values.add("two");
        headers.clear();

        assertEquals(List.of("one"), response.headers().get("X-Test"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> response.headers().put("X-Other", List.of("value")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> response.headers().get("X-Test").add("two"));

        response.close();
        assertTrue(closed.get());
    }

    @Test
    void rejectsInvalidStatusCodes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpstreamResponse(
                        99, Map.of(), new ByteArrayInputStream(new byte[0])));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UpstreamResponse(
                        600, Map.of(), new ByteArrayInputStream(new byte[0])));
    }
}
