package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.config.ProxyConfig;
import com.example.proxy.proxy.ReverseProxyServer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Command-line entry point for the reverse proxy.
 *
 * <p>Loads UTF-8 configuration and runs the health-aware HTTP proxy until shutdown.
 */
public final class ProxyApplication {
    static final Path DEFAULT_CONFIG_PATH = Path.of("config", "proxy.properties");
    static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";

    private ProxyApplication() {
    }

    public static void main(String[] args) {
        PrintStream utf8Out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream utf8Err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int exitCode = run(args, DEFAULT_CONFIG_PATH, utf8Out, utf8Err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, Path defaultConfigPath, PrintStream out, PrintStream err) {
        return run(args, defaultConfigPath, out, err, ProxyApplication::serve);
    }

    @FunctionalInterface
    interface ServerRunner {
        void run(ProxyConfig config, PrintStream out) throws IOException, InterruptedException;
    }

    static int run(String[] args, Path defaultConfigPath, PrintStream out, PrintStream err,
            ServerRunner runner) {
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(defaultConfigPath, "defaultConfigPath must not be null");
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(err, "err must not be null");

        final CliOptions options;
        try {
            options = CliOptions.parse(args, defaultConfigPath);
        } catch (IllegalArgumentException exception) {
            err.println("错误：" + exception.getMessage());
            err.println("使用 --help 查看帮助。");
            return 2;
        }

        if (options.action() == CliOptions.Action.HELP) {
            printHelp(out);
            return 0;
        }
        if (options.action() == CliOptions.Action.VERSION) {
            out.println("jdk-reverse-proxy " + applicationVersion());
            return 0;
        }

        Path configPath = options.configPath().toAbsolutePath().normalize();
        final ProxyConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (IOException | IllegalArgumentException exception) {
            err.printf("错误：无法加载配置文件 %s：%s%n", configPath, exception.getMessage());
            return 1;
        }

        out.printf(
                "已加载配置文件：%s（%d 个后端，监听 %s:%d，策略 %s）%n",
                configPath,
                config.backends().size(),
                config.listenAddress().getHostString(),
                config.listenAddress().getPort(),
                config.loadBalancingStrategy().configValue());
        try {
            runner.run(config, out);
            return 0;
        } catch (IOException | RuntimeException exception) {
            err.println("错误：代理启动或运行失败：" + exception.getMessage());
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("代理已中断并关闭。");
            return 130;
        }
    }

    private static void serve(ProxyConfig config, PrintStream out) throws IOException, InterruptedException {
        try (ReverseProxyServer proxy = ReverseProxyServer.create(config)) {
            Thread shutdownHook = new Thread(proxy::close, "proxy-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                proxy.start();
                out.printf("代理已启动：%s:%d；阶段 5 健康检查与有限重试模式，策略 %s，配置后端 %d 个%n",
                        proxy.address().getHostString(), proxy.address().getPort(),
                        config.loadBalancingStrategy().configValue(), config.backends().size());
                config.backends().forEach(backend -> out.printf("  后端 %s（%s），权重 %d%n",
                        backend.id(), backend.baseUri(), backend.weight()));
                out.printf("后端初始为 UNKNOWN；成功阈值 %d，失败阈值 %d，探测周期 %dms，探测超时 %dms。%n",
                        config.healthSuccessThreshold(), config.healthFailureThreshold(),
                        config.healthInterval().toMillis(), config.healthTimeout().toMillis());
                out.printf("达到成功阈值后才接收流量；幂等请求最多尝试 %d 次（含首次），POST/PATCH 不重试。%n",
                        config.maxAttempts());
                out.printf("连接超时 %dms，单次等待响应头超时 %dms；流式响应体不承诺整体截止时间。%n",
                        config.connectTimeout().toMillis(), config.requestTimeout().toMillis());
                proxy.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException shutdownInProgress) {
                    // The JVM is already executing the hook.
                }
            }
        }
    }

    private static String applicationVersion() {
        String implementationVersion = ProxyApplication.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
            ? FALLBACK_VERSION
            : implementationVersion;
    }

    private static void printHelp(PrintStream out) {
        out.println("简易 HTTP 反向代理与负载均衡器");
        out.println();
        out.println("用法：");
        out.println("  java -jar jdk-reverse-proxy.jar [--config <文件>]");
        out.println("  java -jar jdk-reverse-proxy.jar --help");
        out.println("  java -jar jdk-reverse-proxy.jar --version");
        out.println();
        out.println("选项：");
        out.println("  --config <文件>  指定 UTF-8 Properties 配置文件");
        out.println("                   默认：config/proxy.properties");
        out.println("  -h, --help       显示帮助");
        out.println("  -V, --version    显示版本");
    }
}
