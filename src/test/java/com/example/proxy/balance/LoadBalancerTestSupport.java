package com.example.proxy.balance;

import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

final class LoadBalancerTestSupport {
    private LoadBalancerTestSupport() {
    }

    static BackendSnapshot backend(String id, int weight) {
        return new BackendSnapshot(id, URI.create("http://127.0.0.1:9001/" + id), weight,
                "/health", BackendState.HEALTHY, 1, 0);
    }

    static List<String> selections(LoadBalancer balancer, List<BackendSnapshot> candidates, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> balancer.select(candidates, Set.of()).orElseThrow().id()).toList();
    }
}
