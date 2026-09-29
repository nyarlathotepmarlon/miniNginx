package com.example.proxy.balance;

import java.util.Arrays;
import java.util.Locale;

public enum LoadBalancingStrategy {
    ROUND_ROBIN("round-robin"),
    WEIGHTED_ROUND_ROBIN("weighted-round-robin");

    private final String configValue;

    LoadBalancingStrategy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static LoadBalancingStrategy fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("load-balancer.strategy must not be blank");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(strategy -> strategy.configValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported load-balancer.strategy: " + value));
    }
}
