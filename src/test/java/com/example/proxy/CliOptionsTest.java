package com.example.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliOptionsTest {
    private static final Path DEFAULT_CONFIG = Path.of("config", "proxy.properties");

    @Test
    void usesDefaultConfigWhenNoArgumentsAreProvided() {
        CliOptions options = CliOptions.parse(new String[0], DEFAULT_CONFIG);

        assertEquals(CliOptions.Action.RUN, options.action());
        assertEquals(DEFAULT_CONFIG, options.configPath());
    }

    @Test
    void acceptsExplicitConfigPath() {
        CliOptions options = CliOptions.parse(
                new String[] {"--config", "config/custom.properties"}, DEFAULT_CONFIG);

        assertEquals(CliOptions.Action.RUN, options.action());
        assertEquals(Path.of("config", "custom.properties"), options.configPath());
    }

    @Test
    void acceptsHelpAndVersionAliases() {
        assertEquals(
                CliOptions.Action.HELP,
                CliOptions.parse(new String[] {"-h"}, DEFAULT_CONFIG).action());
        assertEquals(
                CliOptions.Action.VERSION,
                CliOptions.parse(new String[] {"-V"}, DEFAULT_CONFIG).action());
    }

    @Test
    void rejectsMissingConfigPathAndUnknownArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {"--config"}, DEFAULT_CONFIG));
        assertThrows(
                IllegalArgumentException.class,
                () -> CliOptions.parse(new String[] {"--unknown"}, DEFAULT_CONFIG));
    }
}
