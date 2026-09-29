package com.example.proxy;

import com.example.proxy.config.ConfigLoader;
import com.example.proxy.config.ProxyConfig;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Command-line entry point for the reverse proxy.
 *
 * <p>The application validates the command line and loads a strongly typed UTF-8 configuration.
 * Runtime proxy components are added in the following stages.
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
        out.println("配置与后端模型初始化完成；HTTP 代理运行组件将在后续阶段接入。");
        return 0;
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
