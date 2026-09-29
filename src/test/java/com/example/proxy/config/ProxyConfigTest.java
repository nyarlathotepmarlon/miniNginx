package com.example.proxy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.proxy.balance.LoadBalancingStrategy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProxyConfigTest {
    @Test
    void permitsPortZeroForProgrammaticIntegrationTestBinding() {
        assertDoesNotThrow(() -> config(
                new InetSocketAddress("127.0.0.1", 0),
                new ArrayList<>(List.of(backend("primary"))),
                new LinkedHashSet<>(Set.of(503))));
    }

    @Test
    void makesBackendAndRetryCollectionsImmutableDefensiveCopies() {
        List<BackendConfig> backends = new ArrayList<>(List.of(backend("primary")));
        Set<Integer> statuses = new LinkedHashSet<>(Set.of(502, 503));

        ProxyConfig config = config(new InetSocketAddress("127.0.0.1", 8080), backends, statuses);
        backends.clear();
        statuses.clear();

        assertEquals(1, config.backends().size());
        assertEquals(Set.of(502, 503), config.retryStatusCodes());
        assertThrows(UnsupportedOperationException.class, () -> config.backends().clear());
        assertThrows(UnsupportedOperationException.class, () -> config.retryStatusCodes().clear());
    }

    @Test
    void rejectsDuplicateBackendIds() {
        List<BackendConfig> duplicates = List.of(backend("primary"), backend("primary"));

        assertThrows(
                IllegalArgumentException.class,
                () -> config(
                        new InetSocketAddress("127.0.0.1", 8080),
                        new ArrayList<>(duplicates),
                        new LinkedHashSet<>(Set.of(503))));
    }

    private static ProxyConfig config(
            InetSocketAddress address, List<BackendConfig> backends, Set<Integer> statuses) {
        return new ProxyConfig(
                address,
                4,
                LoadBalancingStrategy.ROUND_ROBIN,
                backends,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                2,
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                1024,
                2,
                statuses,
                "/_proxy");
    }

    private static BackendConfig backend(String id) {
        return new BackendConfig(id, URI.create("http://127.0.0.1:9001"), 1, "/health");
    }
}
