package com.example.proxy.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LoadBalancingStrategyTest {
    @Test
    void parsesStableConfigurationNamesCaseInsensitively() {
        assertEquals(
                LoadBalancingStrategy.ROUND_ROBIN,
                LoadBalancingStrategy.fromConfigValue("ROUND-ROBIN"));
        assertEquals(
                LoadBalancingStrategy.WEIGHTED_ROUND_ROBIN,
                LoadBalancingStrategy.fromConfigValue(" weighted-round-robin "));
    }

    @Test
    void rejectsUnknownAndBlankStrategies() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LoadBalancingStrategy.fromConfigValue("random"));
        assertThrows(
                IllegalArgumentException.class,
                () -> LoadBalancingStrategy.fromConfigValue(" "));
    }
}
