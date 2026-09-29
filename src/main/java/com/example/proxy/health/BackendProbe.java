package com.example.proxy.health;

import com.example.proxy.config.BackendConfig;
import java.io.IOException;

/** A single interruptible health probe; false or an exception means failure. */
@FunctionalInterface
public interface BackendProbe {
    boolean check(BackendConfig backend) throws IOException, InterruptedException;
}
