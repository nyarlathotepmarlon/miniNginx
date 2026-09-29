package com.example.proxy.backend;

import java.util.List;

/** Supplies an immutable, ordered snapshot of backends eligible for request selection. */
@FunctionalInterface
public interface BackendPool {
    List<BackendSnapshot> candidates();
}
