package com.example.proxy.balance;

import com.example.proxy.backend.BackendSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Selects one backend while excluding instances already attempted for the current request. */
@FunctionalInterface
public interface LoadBalancer {
    Optional<BackendSnapshot> select(
            List<BackendSnapshot> candidates, Set<String> excludedBackendIds);
}
