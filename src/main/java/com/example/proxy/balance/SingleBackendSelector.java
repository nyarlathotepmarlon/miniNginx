package com.example.proxy.balance;

import com.example.proxy.backend.BackendSnapshot;
import com.example.proxy.backend.BackendState;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Stage 2 selects the first eligible backend, with no load-balancing or retry loop. */
public final class SingleBackendSelector implements LoadBalancer {
    @Override
    public Optional<BackendSnapshot> select(
            List<BackendSnapshot> candidates, Set<String> excludedBackendIds) {
        return candidates.stream()
                .filter(backend -> backend.state() == BackendState.HEALTHY)
                .filter(backend -> !excludedBackendIds.contains(backend.id()))
                .findFirst();
    }
}
