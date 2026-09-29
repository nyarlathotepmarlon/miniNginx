package com.example.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.proxy.balance.LoadBalancingStrategy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigLoaderTest {
    private Path temporaryDirectory;

    @BeforeEach
    void createWorkspaceLocalTemporaryDirectory() throws Exception {
        Path testDataDirectory = Path.of("target", "test-data");
        Files.createDirectories(testDataDirectory);
        temporaryDirectory = Files.createTempDirectory(testDataDirectory, "config-loader-");
    }

    @Test
    void appliesDocumentedDefaults() {
        ProxyConfig config = ConfigLoader.fromProperties(minimalProperties());

        assertEquals("127.0.0.1", config.listenAddress().getHostString());
        assertEquals(8080, config.listenAddress().getPort());
        assertEquals(
                Math.max(8, Runtime.getRuntime().availableProcessors() * 2),
                config.workerThreads());
        assertEquals(LoadBalancingStrategy.ROUND_ROBIN, config.loadBalancingStrategy());
        assertEquals(1, config.backends().size());
        assertEquals(1, config.backends().get(0).weight());
        assertEquals("/health", config.backends().get(0).healthPath());
        assertEquals(Duration.ofSeconds(5), config.healthInterval());
        assertEquals(Duration.ofSeconds(1), config.healthTimeout());
        assertEquals(2, config.healthFailureThreshold());
        assertEquals(1, config.healthSuccessThreshold());
        assertEquals(Duration.ofSeconds(1), config.connectTimeout());
        assertEquals(Duration.ofSeconds(3), config.requestTimeout());
        assertEquals(10L * 1024 * 1024, config.maxRequestBodyBytes());
        assertEquals(2, config.maxAttempts());
        assertEquals(Set.of(502, 503, 504), config.retryStatusCodes());
        assertEquals("/_proxy", config.managementPathPrefix());
    }

    @Test
    void parsesAllOverridesAndPreservesBackendOrder() {
        Properties properties = new Properties();
        properties.setProperty("listen.host", "0.0.0.0");
        properties.setProperty("listen.port", "8088");
        properties.setProperty("proxy.worker-threads", "3");
        properties.setProperty("load-balancer.strategy", "WEIGHTED-ROUND-ROBIN");
        properties.setProperty("backends", "api-a, api-b");
        properties.setProperty("backend.api-a.url", "http://127.0.0.1:9001/base");
        properties.setProperty("backend.api-a.weight", "3");
        properties.setProperty("backend.api-a.health-path", "/ready");
        properties.setProperty("backend.api-b.url", "https://example.com/service");
        properties.setProperty("backend.api-b.weight", "2");
        properties.setProperty("backend.api-b.health-path", "/live");
        properties.setProperty("health.interval-ms", "6000");
        properties.setProperty("health.timeout-ms", "1200");
        properties.setProperty("health.failure-threshold", "4");
        properties.setProperty("health.success-threshold", "2");
        properties.setProperty("proxy.connect-timeout-ms", "1300");
        properties.setProperty("proxy.request-timeout-ms", "4500");
        properties.setProperty("proxy.max-request-body-bytes", "2048");
        properties.setProperty("retry.max-attempts", "3");
        properties.setProperty("retry.status-codes", "429, 503");
        properties.setProperty("management.path-prefix", "/management");

        ProxyConfig config = ConfigLoader.fromProperties(properties);

        assertEquals("0.0.0.0", config.listenAddress().getHostString());
        assertEquals(8088, config.listenAddress().getPort());
        assertEquals(3, config.workerThreads());
        assertEquals(
                LoadBalancingStrategy.WEIGHTED_ROUND_ROBIN,
                config.loadBalancingStrategy());
        assertEquals("api-a", config.backends().get(0).id());
        assertEquals(URI.create("http://127.0.0.1:9001/base"), config.backends().get(0).baseUri());
        assertEquals(3, config.backends().get(0).weight());
        assertEquals("api-b", config.backends().get(1).id());
        assertEquals(Duration.ofMillis(6000), config.healthInterval());
        assertEquals(Duration.ofMillis(1200), config.healthTimeout());
        assertEquals(4, config.healthFailureThreshold());
        assertEquals(2, config.healthSuccessThreshold());
        assertEquals(Duration.ofMillis(1300), config.connectTimeout());
        assertEquals(Duration.ofMillis(4500), config.requestTimeout());
        assertEquals(2048, config.maxRequestBodyBytes());
        assertEquals(3, config.maxAttempts());
        assertEquals(Set.of(429, 503), config.retryStatusCodes());
        assertEquals("/management", config.managementPathPrefix());
    }

    @Test
    void loadsUtf8BackendIdentifiersFromDisk() throws Exception {
        Path configFile = temporaryDirectory.resolve("代理.properties");
        Files.writeString(
                configFile,
                "backends=主节点\nbackend.主节点.url=http://127.0.0.1:9001\n",
                StandardCharsets.UTF_8);

        ProxyConfig config = ConfigLoader.load(configFile);

        assertEquals("主节点", config.backends().get(0).id());
    }

    @Test
    void loadsTheProjectDefaultConfiguration() throws Exception {
        ProxyConfig config = ConfigLoader.load(Path.of("config", "proxy.properties"));

        assertEquals(2, config.backends().size());
        assertEquals("api-a", config.backends().get(0).id());
        assertEquals("api-b", config.backends().get(1).id());
    }

    @Test
    void loadsTheStageThreeWeightedExample() throws Exception {
        ProxyConfig config = ConfigLoader.load(Path.of("config", "proxy-weighted.properties"));
        assertEquals(LoadBalancingStrategy.WEIGHTED_ROUND_ROBIN, config.loadBalancingStrategy());
        assertEquals(2, config.backends().size());
        assertEquals(3, config.backends().get(0).weight());
        assertEquals(1, config.backends().get(1).weight());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "not-a-port"})
    void rejectsInvalidProductionListenPorts(String port) {
        Properties properties = minimalProperties();
        properties.setProperty("listen.port", port);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigLoader.fromProperties(properties));

        assertTrue(exception.getMessage().contains("listen.port"));
    }

    @Test
    void rejectsMissingEmptyAndDuplicateBackendIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(new Properties()));

        Properties empty = minimalProperties();
        empty.setProperty("backends", "api-a,,api-b");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(empty));

        Properties duplicate = minimalProperties();
        duplicate.setProperty("backends", "primary,primary");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(duplicate));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://127.0.0.1:9001",
        "http://127.0.0.1:9001/base?query=1",
        "http://127.0.0.1:9001/base#fragment",
        "http://user:secret@127.0.0.1:9001",
        "http://127.0.0.1:0",
        "http://127.0.0.1:65536",
        "/relative/path"
    })
    void rejectsInvalidBackendUrls(String url) {
        Properties properties = minimalProperties();
        properties.setProperty("backend.primary.url", url);

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(properties));
    }

    @Test
    void boundsRequestBufferToAJavaByteArray() {
        Properties properties = minimalProperties();
        properties.setProperty("proxy.max-request-body-bytes", "2147483639");
        assertEquals(2147483639L, ConfigLoader.fromProperties(properties).maxRequestBodyBytes());
        properties.setProperty("proxy.max-request-body-bytes", "2147483640");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(properties));
        properties.setProperty("proxy.max-request-body-bytes", Long.toString(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(properties));
    }

    @ParameterizedTest
    @MethodSource("positiveNumericSettings")
    void rejectsNonPositiveNumericSettings(String key) {
        Properties properties = minimalProperties();
        properties.setProperty(key, "0");

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(properties));
    }

    static Stream<Arguments> positiveNumericSettings() {
        return Stream.of(
                Arguments.of("proxy.worker-threads"),
                Arguments.of("backend.primary.weight"),
                Arguments.of("health.interval-ms"),
                Arguments.of("health.timeout-ms"),
                Arguments.of("health.failure-threshold"),
                Arguments.of("health.success-threshold"),
                Arguments.of("proxy.connect-timeout-ms"),
                Arguments.of("proxy.request-timeout-ms"),
                Arguments.of("proxy.max-request-body-bytes"),
                Arguments.of("retry.max-attempts"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"proxy", "/", "/_proxy/", "/_proxy?query=1", "//authority"})
    void rejectsInvalidManagementPathPrefixes(String prefix) {
        Properties properties = minimalProperties();
        properties.setProperty("management.path-prefix", prefix);

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.fromProperties(properties));
    }

    @Test
    void validatesHealthPathsStrategiesAndRetryStatusCodes() {
        Properties invalidHealthPath = minimalProperties();
        invalidHealthPath.setProperty("backend.primary.health-path", "/health?detail=true");
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigLoader.fromProperties(invalidHealthPath));

        Properties invalidStrategy = minimalProperties();
        invalidStrategy.setProperty("load-balancer.strategy", "random");
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigLoader.fromProperties(invalidStrategy));

        Properties invalidStatus = minimalProperties();
        invalidStatus.setProperty("retry.status-codes", "200,503");
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigLoader.fromProperties(invalidStatus));

        Properties disabledStatusRetry = minimalProperties();
        disabledStatusRetry.setProperty("retry.status-codes", " ");
        assertTrue(ConfigLoader.fromProperties(disabledStatusRetry).retryStatusCodes().isEmpty());
    }

    private static Properties minimalProperties() {
        Properties properties = new Properties();
        properties.setProperty("backends", "primary");
        properties.setProperty("backend.primary.url", "http://127.0.0.1:9001");
        return properties;
    }
}
