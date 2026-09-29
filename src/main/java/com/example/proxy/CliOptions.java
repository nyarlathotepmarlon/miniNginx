package com.example.proxy;

import java.nio.file.Path;
import java.util.Objects;

record CliOptions(Action action, Path configPath) {
    enum Action {
        RUN,
        HELP,
        VERSION
    }

    CliOptions {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(configPath, "configPath must not be null");
    }

    static CliOptions parse(String[] args, Path defaultConfigPath) {
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(defaultConfigPath, "defaultConfigPath must not be null");

        if (args.length == 0) {
            return new CliOptions(Action.RUN, defaultConfigPath);
        }
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            return new CliOptions(Action.HELP, defaultConfigPath);
        }
        if (args.length == 1 && ("--version".equals(args[0]) || "-V".equals(args[0]))) {
            return new CliOptions(Action.VERSION, defaultConfigPath);
        }
        if (args.length == 2 && "--config".equals(args[0])) {
            if (args[1].isBlank()) {
                throw new IllegalArgumentException("--config 后必须提供非空文件路径");
            }
            return new CliOptions(Action.RUN, Path.of(args[1]));
        }

        if ("--config".equals(args[0]) && args.length == 1) {
            throw new IllegalArgumentException("--config 后缺少文件路径");
        }
        throw new IllegalArgumentException("不支持的参数组合：" + String.join(" ", args));
    }
}
