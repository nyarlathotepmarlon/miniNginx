# 阶段 6：系统验收与交付记录

验收日期：2026-09-29。结论：本阶段的干净构建、文档、配置模板、可执行 JAR 和十项功能场景均已验证，等待人工审核。本次不执行 Git 提交或推送。

## 1. 本阶段交付

- [README](../README.md)：环境要求、Windows UTF-8 设置、构建启动、curl、全部配置、故障演示、测试命令及已知限制。
- [架构设计](design.md)：模块职责、请求流程、双向头过滤、原始 URI、响应流、两种算法、健康状态机、重试与错误映射、并发容量、资源所有权及生命周期。
- [可复制配置模板](../config/proxy-example.properties)：UTF-8 中文注释，配置值与默认 `proxy.properties` 一致；新增单元测试验证模板可读且加载结果一致。
- [独立 JAR 验收程序](../src/test/java/com/example/proxy/support/JarSmokeCheck.java)：每种策略检查 400 次分配、停机恢复、真实业务超时、POST 不重试、请求体超限、全不可用及最终日志；运行结果自动保存在 `result.txt`。
- 启动消息移除过时的“阶段 5”标签，不修改代理功能语义；阶段 5 文档保留历史记录并补充最终文档导航。

核心转发、均衡、健康检查、重试和访问日志沿用阶段 2–5 的实现，本阶段没有新增可选模块或扩大协议范围。

## 2. 环境与可重复命令

本轮实际环境：

| 项目 | 版本或设置 |
| --- | --- |
| 操作系统 | Windows 11，amd64 |
| JDK | Eclipse Temurin 17.0.19+10 |
| Maven | 3.9.16 |
| 区域 / 编码 | zh_CN / UTF-8 |
| 编译目标 | Java release 17 |
| 测试框架 | JUnit 5.14.4，Surefire 3.6.0 |

在仓库根目录执行以下 PowerShell 命令，每条成功后再执行下一条：

```powershell
java -version
mvn -version

# 实际清理 target 后，重新编译全部源码和测试
mvn clean test

# 只跳过重复执行刚通过的测试，生成可执行 JAR
mvn "-DskipTests" package

java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --help
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --version

# 每条命令自动创建临时后端、配置并以独立进程运行实际 JAR
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar round-robin
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar weighted-round-robin
```

也可用 `mvn clean package` 一次完成测试与打包；`mvn clean test` 本身不生成 JAR。首次构建需要可用的 Maven 依赖缓存或下载网络，测试与双后端验收本身只访问本机。

验收程序不用固定的 8080/9001/9002：后端绑定回环临时端口，独立代理的正式配置端口先通过本地端口预留获得，再交给子进程绑定。预留释放与子进程绑定之间仍有很小的端口竞争窗口；如果其他本机程序恰好占用该端口，重新运行验收即可。生产配置仍禁止端口 0。

为缩短测试时间，验收配置使用 4 个工作线程、750ms 业务超时、100ms 健康周期、500ms 探测超时、连续成功/失败阈值均为 2，重试最多 2 次。它不改变正式示例和代码默认值。慢后端用可释放的同步门闩保持业务响应头未返回，健康接口持续正常；测试按观测到的健康状态推进，不以固定 sleep 猜测摘除/恢复已完成。

## 3. 干净构建与自动化测试结果

`mvn clean test` 于本地时间 12:27:45 完成，耗时约 1 分 42 秒：**22 个测试类、259 项测试，0 失败、0 错误、0 跳过**。构建日志确认先删除旧 `target`，重新编译 33 个主源码文件和 27 个测试/辅助源码文件，不依赖历史 class。

| 测试分组 | 数量 | 覆盖重点 |
| --- | ---: | --- |
| CLI 与入口 | 10 | 帮助、版本、默认配置、中文读取、非法参数与启动失败 |
| 配置与不可变模型 | 37 | 默认值、完整配置、非法边界、模板与默认示例一致 |
| 后端注册表、探测与健康生命周期 | 35 | UNKNOWN、阈值、真实摘除恢复、独立探测、异常隔离、并发关闭 |
| 均衡算法及真实多后端转发 | 34 | RR/SWRR 序列、比例、溢出、上下线与并发 |
| HTTP 转发与 URI | 41 | 方法、二进制正文、动态逐跳头、编码、重定向、体积限制、流中断 |
| 重试策略、传输与真实故障 | 99 | 方法×故障矩阵、不同实例排除、最终响应/错误、真实线上请求次数 |
| 日志与线程池关闭 | 3 | 字段、敏感信息保护及有界关闭 |
| 合计 | 259 | 全部通过 |

独立 JAR 验收不计入这 259 项；两种策略分别运行并返回退出码 0、输出 `JAR_SMOKE_OK`。

## 4. 十项最终场景对照

表中 A/B 分别对应验收程序的 primary/secondary，实际配置 ID 为“主节点/备节点”。

| 序号 | 计划场景 | 本轮证据与结果 |
| --- | --- | --- |
| 1 | 首次健康检查后才开始转发 | JAR 等待两个实例达到成功阈值后转发成功；`HealthLifecycleIntegrationTest` 另行验证首次探测完成前及成功次数不足时业务返回 503 |
| 2 | 普通轮询 A、B、A、B | JAR 首四次为 A/B/A/B，逐次校验全部 400 次；A/B=200/200 |
| 3 | 权重 3:1 的长期分布 | 加权 JAR 首四次为 A/A/B/A，全部 400 次逐次正确；A/B=300/100 |
| 4 | 停止 A，达到失败阈值后只访问 B | 实际关闭 A 的监听端口，健康数量降为 1 后连续 4 次全为 B；两种策略均通过 |
| 5 | 原端口重启 A 后恢复均衡 | 实际重新绑定 A，健康数量恢复为 2；RR 恢复 A/B/A/B，SWRR 恢复 A/A/B/A |
| 6 | A 超时后 GET 切换 B | A 的健康探测正常但业务不返回头；A/B 各收到一次 GET，最终 200，日志 attempts=2、backend=备节点 |
| 7 | A 对 POST 超时，不重试 | A 仅收到一次 POST，B 未收到 POST；最终 504 / UPSTREAM_TIMEOUT，日志 attempts=1 |
| 8 | 全部实例不可用返回 503 | 实际关闭两个后端，健康数量为 0；健康与业务均 503，业务 attempts=0 / NO_BACKEND |
| 9 | 超过 10 MiB 返回 413 | JAR 收到声明长度 10485761 后立即拒绝，不等待上传、不调用后端；JUnit 同时验证有/无 Content-Length 的实际读取超限及边界值 |
| 10 | 从空 target 测试并生成可运行 JAR | 干净测试 259 项通过，随后打包成功；最终 JAR 的 Manifest、CLI 和上述实际进程转发全部通过 |

第 9 项区分了两种证据：默认 10 MiB 边界使用声明长度验证“尽早拒绝”；实际未知长度正文读取使用较小可配置上限作确定性测试，并非发送了 10 MiB 的未知长度正文作压力测试。

其他关键回归也已通过：POST/PATCH 在断连接时不重放、JDK 内部重试的独立 JVM 验证、最终合法 503 响应保留、提交后断流不重试且不伪造结束块、HEAD/204/205/304 无正文、管理前缀边界、重复关闭及活动流释放。

## 5. 实际 JAR 验收输出与证据目录

普通轮询：

```text
JAR_SMOKE_OK: round-robin first=[primary, secondary, primary, secondary]; distribution=200:200; stopped=[secondary, secondary, secondary, secondary]; restored=[primary, secondary, primary, secondary]; GET timeout->backup=200/attempts=2; POST timeout=504/attempts=1; oversized=413/attempts=0; all-down=503/attempts=0; UTF-8, raw URI, health transitions and final access logs verified
```

平滑加权轮询：

```text
JAR_SMOKE_OK: weighted-round-robin first=[primary, primary, secondary, primary]; distribution=300:100; stopped=[secondary, secondary, secondary, secondary]; restored=[primary, primary, secondary, primary]; GET timeout->backup=200/attempts=2; POST timeout=504/attempts=1; oversized=413/attempts=0; all-down=503/attempts=0; UTF-8, raw URI, health transitions and final access logs verified
```

本轮最终证据位置（相对于仓库根目录）：

- JUnit XML 与文本报告：`target/surefire-reports/`。
- 普通轮询：`target/test-data/jar-smoke-5846445741552944201/`。
- 平滑加权轮询：`target/test-data/jar-smoke-13259027272377263620/`。

每个 JAR 验收目录包含 `代理配置.properties`、`stdout.log`、`access.log`、`result.txt`。目录名每次重新生成；这些构建产物被 Git 忽略，下一次 `mvn clean` 会删除。长期证据以本文为准，需要原始日志时请先另行归档。

## 6. 可执行交付物与 CLI

- 文件：`target/jdk-reverse-proxy-0.1.0-SNAPSHOT.jar`。
- Manifest：`Main-Class: com.example.proxy.ProxyApplication`，`Build-Jdk-Spec: 17`，`Implementation-Version: 0.1.0-SNAPSHOT`。
- `--help` 返回 0，显示中文帮助；`--version` 返回 0，输出 `jdk-reverse-proxy 0.1.0-SNAPSHOT`。
- 指向不存在的中文配置路径时，打印“无法加载配置文件”，实际退出码为 1。

本轮构建 JAR 的 SHA-256：

```text
B3ED23214A5D626DDB371846976B97421F6B4F7310176500751FDD539B356B2B
```

该散列只标识本轮产物，不承诺不同构建时间得到相同字节。源码、配置模板与设计文档是可重建交付，JAR 不纳入 Git。

## 7. Windows / 中文 / 日志检查

- Maven 实际运行于 Java 17、zh_CN / UTF-8；源码、配置和文档按严格 UTF-8 解码并检查无 BOM、无替换字符。
- 用包含中文的配置文件名及后端 ID 启动独立 JAR，启动信息和状态变化日志正确保留中文。
- 请求正文“中文请求体 / binary-safe forwarding”往返不变；`/api` 基准前缀、`a%2Fb` 路径与 `q=a%2Bb` 查询未被重新编码。
- 每个策略 408 次正常回显请求均记录 attempts=1 / status=201；超时切换、POST 超时、超限及不可用请求分别按 requestId 验证仅一条最终日志，并检查最终后端、尝试次数和错误分类。
- 日志中不出现测试用 Authorization、Cookie、查询令牌或请求正文；健康变化包含 UNKNOWN→HEALTHY、摘除及 UNHEALTHY→HEALTHY 恢复。
- Markdown 代码块配对、仓库内文件链接和 `git diff --check` 均作交付前检查。

## 8. 保留的边界与审核

本轮没有进行公网依赖测试、生产负载压测或完整协议认证。请求超时仍主要约束等待上游响应头，不覆盖完整流式响应、慢客户端上传/下载和排队；重试不保证后端“只执行一次”。固定工作池和有界队列可以限制任务堆积，但过载可能直接断连，管理请求也可能被慢业务阻塞。

管理接口没有认证，监听端只支持 HTTP；正式使用不应绕过默认回环地址的访问限制而不做部署层保护。限流、Web 管理界面、Docker、HTTPS 终止等继续不在首版范围内。

阶段 6 到此完成，不自动开启下一阶段或提交 Git。请优先审核 README 的运行步骤、设计文档的超时/重试边界，以及本页十项场景和实际结果。
