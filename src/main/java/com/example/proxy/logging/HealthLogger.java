package com.example.proxy.logging;

import com.example.proxy.backend.BackendStatusUpdate;
import java.time.Instant;
import java.util.logging.Logger;

/** Receives state transitions only, not a message for each periodic probe. */
@FunctionalInterface
public interface HealthLogger {
    void log(BackendStatusUpdate update);

    static HealthLogger console() {
        return Console.INSTANCE;
    }

    final class Console {
        private static final HealthLogger INSTANCE = create();

        private Console() {
        }

        private static HealthLogger create() {
            Logger logger = LogSupport.console("com.example.proxy.health");
            return update -> logger.info("timestamp=" + Instant.now() + " event=backend_health backend="
                    + LogSupport.quote(update.current().id()) + " from=" + update.previous().state()
                    + " to=" + update.current().state());
        }
    }
}
