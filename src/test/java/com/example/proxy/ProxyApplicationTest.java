package com.example.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProxyApplicationTest {
    Path temporaryDirectory;

    @BeforeEach
    void createWorkspaceLocalTemporaryDirectory() throws Exception {
        Path testDataDirectory = Path.of("target", "test-data");
        Files.createDirectories(testDataDirectory);
        temporaryDirectory = Files.createTempDirectory(testDataDirectory, "proxy-application-");
    }

    @Test
    void printsHelpWithoutLoadingAConfigFile() {
        Result result = run(new String[] {"--help"}, temporaryDirectory.resolve("missing.properties"));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("--config <文件>"));
        assertEquals("", result.stderr());
    }

    @Test
    void printsVersionWithoutLoadingAConfigFile() {
        Result result = run(new String[] {"--version"}, temporaryDirectory.resolve("missing.properties"));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(ProxyApplication.FALLBACK_VERSION));
        assertEquals("", result.stderr());
    }

    @Test
    void loadsTheDefaultPropertiesFileAsUtf8() throws Exception {
        Path configFile = temporaryDirectory.resolve("代理配置.properties");
        Files.writeString(
                configFile,
                "backends=主节点\nbackend.主节点.url=http://127.0.0.1:9001\n",
                StandardCharsets.UTF_8);

        Result result = run(new String[0], configFile);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(configFile.toAbsolutePath().toString()));
        assertTrue(result.stdout().contains("1 个后端"));
        assertTrue(result.stdout().contains("策略 round-robin"));
        assertEquals("", result.stderr());
    }

    @Test
    void reportsMissingAndEmptyConfigFiles() throws Exception {
        Result missing = run(new String[0], temporaryDirectory.resolve("missing.properties"));
        assertEquals(1, missing.exitCode());
        assertTrue(missing.stderr().contains("无法加载配置文件"));

        Path emptyFile = temporaryDirectory.resolve("empty.properties");
        Files.createFile(emptyFile);
        Result empty = run(new String[] {"--config", emptyFile.toString()}, temporaryDirectory.resolve("unused"));
        assertEquals(1, empty.exitCode());
        assertTrue(empty.stderr().contains("backends"));
    }

    @Test
    void reportsInvalidArgumentsWithUsageHint() {
        Result result = run(new String[] {"--unknown"}, temporaryDirectory.resolve("unused"));

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("不支持的参数组合"));
        assertTrue(result.stderr().contains("--help"));
    }

    private Result run(String[] args, Path defaultConfigPath) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = ProxyApplication.run(args, defaultConfigPath, out, err);
        }
        return new Result(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
