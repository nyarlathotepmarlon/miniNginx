package com.example.proxy.backend;

import java.util.Objects;

public record BackendStatusUpdate(BackendSnapshot previous, BackendSnapshot current) {
    public BackendStatusUpdate {
        Objects.requireNonNull(previous, "previous must not be null");
        Objects.requireNonNull(current, "current must not be null");
        if (!previous.id().equals(current.id())) {
            throw new IllegalArgumentException("status update snapshots must describe the same backend");
        }
    }

    public boolean stateChanged() {
        return previous.state() != current.state();
    }
}
