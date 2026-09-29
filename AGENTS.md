# 简易 HTTP 反向代理与负载均衡器实施阶段计划

## 1. 方案摘要与交付边界

首版交付核心必做能力：HTTP 转发、多后端、普通轮询、平滑加权轮询、主动健康检查、自动摘除与恢复、请求超时、有限重试、访问日志、Properties 配置、简易 CLI、自动化测试和设计文档。

技术基线确定为：

- Java 17、Maven、JUnit 5，修改 `pom.xml` 的 `maven.compiler.release` 为 `17`，确保当前 Windows 环境可以直接构建。
- 仅使用 JDK 原生能力：入站使用 `HttpServer`，出站使用 `HttpClient`，日志使用 `java.util.logging`。
- Java 17 下使用固定工作线程池，不使用虚拟线程。
- 首版不包含限流、Web 管理界面、Docker、HTTPS 终止、WebSocket、配置热加载和服务发现。
- 支持 HTTP/HTTPS 后端，但代理监听端首版仅提供 HTTP。
- 所有源码、配置、日志和文档使用 UTF-8。

## 2. 分阶段实施

### 阶段 0：恢复可维护的工程基线

实施内容：

- 将项目编译基线从 Java 21 调整为 Java 17。
- 重建标准的 `src/main/java`、`src/test/java`、配置示例和文档目录。
- 创建 `ProxyApplication` 入口和基础生命周期骨架。
- CLI 支持：
    - `--config <path>`
    - `--help`
    - `--version`
- 无参数启动时读取 `config/proxy.properties`；文件不存在或配置非法时打印明确错误并以非零状态退出。
- 不依赖现有 `target` 中的历史 class 或测试报告；所有成果必须能通过 `mvn clean` 从源码重新生成。

阶段出口：

- `mvn clean test` 在 JDK 17 下成功。
- `java -jar ... --help` 和 `--version` 可执行。
- JAR Manifest 中的入口类正确。

### 阶段 1：配置模型与后端状态模型

实施内容：

- 建立不可变配置模型，包括监听配置、后端列表、负载均衡策略、健康检查、请求超时、重试和请求体限制。
- 建立 `BackendState`：`UNKNOWN`、`HEALTHY`、`UNHEALTHY`。
- 后端启动时均为 `UNKNOWN`；首次健康检查成功后才进入流量池。
- 建立线程安全的后端注册表，向负载均衡器提供不可变的健康后端快照。
- 完成所有启动期校验：
    - 监听端口为 `1–65535`。
    - 至少配置一个后端。
    - 后端名称唯一。
    - 后端 URI 仅允许 `http` 或 `https`，必须包含 host。
    - 权重、超时、检查周期和阈值均为正数。
    - 管理路径必须以 `/` 开头。
    - 后端基准 URI 不允许 query 或 fragment。

配置格式固定为：

```properties
listen.host=127.0.0.1
listen.port=8080
proxy.worker-threads=16

load-balancer.strategy=round-robin

backends=api-a,api-b

backend.api-a.url=http://127.0.0.1:9001
backend.api-a.weight=3
backend.api-a.health-path=/health

backend.api-b.url=http://127.0.0.1:9002
backend.api-b.weight=1
backend.api-b.health-path=/health

health.interval-ms=5000
health.timeout-ms=1000
health.failure-threshold=2
health.success-threshold=1

proxy.connect-timeout-ms=1000
proxy.request-timeout-ms=3000
proxy.max-request-body-bytes=10485760

retry.max-attempts=2
retry.status-codes=502,503,504

management.path-prefix=/_proxy
```

默认值：

- `listen.host=127.0.0.1`
- `listen.port=8080`
- `proxy.worker-threads=max(8, CPU 核数 × 2)`
- `load-balancer.strategy=round-robin`
- 后端权重为 `1`
- 健康路径为 `/health`
- 健康检查周期为 `5000ms`
- 健康检查超时为 `1000ms`
- 连续失败阈值为 `2`
- 连续成功阈值为 `1`
- 连接超时为 `1000ms`
- 单次请求超时为 `3000ms`
- 请求体上限为 `10 MiB`
- 最大尝试次数为 `2`，包含第一次请求
- 管理路径前缀为 `/_proxy`

阶段出口：

- 配置正常值、默认值和全部非法边界均有单元测试。
- 后端状态和健康快照可在线程安全条件下读取、更新。

### 阶段 2：两种负载均衡策略

实施内容：

- 定义统一的 `LoadBalancer` 选择接口，输入健康后端快照和本次请求已尝试的后端集合，输出一个未尝试的后端或空结果。
- 实现普通轮询：
    - 使用线程安全计数器。
    - 使用 `Math.floorMod` 处理整数溢出。
    - 只选择 `HEALTHY` 后端。
- 实现平滑加权轮询：
    - 使用标准 smooth weighted round-robin 算法。
    - 后端权重必须为正整数。
    - 状态更新使用锁保护，避免并发破坏累计权重。
    - 后端摘除或恢复后，根据当前健康快照重建算法状态。
- 一个请求重试时不得再次选择已经尝试过的后端。
- 有效尝试次数为 `min(retry.max-attempts, 本次请求可用的不同健康后端数)`。

阶段出口：

- 轮询顺序测试稳定通过。
- 加权策略对权重 `3:1` 的确定性序列和长期分布测试通过。
- 空列表、单实例、后端上下线、计数器溢出和并发选择均有覆盖。

### 阶段 3：完整 HTTP 转发链路

实施内容：

- `ReverseProxyServer` 使用固定工作线程池接收请求，并实现 `AutoCloseable`。
- `RequestForwarder` 完成方法、路径、原始查询串、请求体和安全请求头的转发。
- 后端 URI 拼接采用固定规则：
    - 后端 URL 中的 path 作为前缀保留。
    - 客户端原始 path 追加到该前缀后。
    - 中间只保留一个 `/`。
    - 原始 query 原样附加。
    - 避免 `URI.resolve` 因客户端路径以 `/` 开头而丢失后端基准路径。
- 请求体先缓冲到内存，最大 `10 MiB`；超过限制立即返回 `413 Payload Too Large`。
- 过滤静态逐跳头以及 `Connection` 头中动态声明的逐跳头。
- 不复制客户端的 `Host`、`Content-Length` 和 `Transfer-Encoding`，由 JDK HTTP 客户端重新生成。
- 添加或追加：
    - `X-Forwarded-For`
    - `X-Forwarded-Host`
    - `X-Forwarded-Proto`
    - `X-Request-Id`
- `HttpClient` 禁止自动跟随重定向。
- 将后端状态码、安全响应头和响应体返回客户端。
- `HEAD`、`204`、`304` 不发送响应体。
- 收到后端响应头后开始向客户端发送响应；响应已经提交后发生的流中断不再重试。
- 无健康后端时返回 `503 Service Unavailable`。
- 保留管理接口 `GET /_proxy/health`：
    - 至少一个健康后端时返回 `200`。
    - 没有健康后端时返回 `503`。
    - JSON 返回代理状态、总后端数和健康后端数。
    - 其他 `/_proxy/*` 路径返回 `404`，不转发到业务后端。

阶段出口：

- 本地模拟后端能够收到一致的 method、raw path、raw query、headers 和 body。
- 客户端能够收到一致的后端状态码、响应头和响应体。
- GET、POST、PUT、DELETE、PATCH、HEAD、OPTIONS、空请求体、二进制请求体和重定向响应均有集成测试。

### 阶段 4：主动健康检查与生命周期

实施内容：

- 使用独立的 `ScheduledThreadPoolExecutor`，线程数为 `min(max(1, 后端数量), 4)`。
- 代理启动后立即对每个后端执行首次检查，之后按固定周期重复。
- 健康请求使用 `GET`，目标为后端基准 URL和 `health-path` 的组合。
- `200–399` 视为检查成功；连接失败、超时和其他状态码视为失败。
- 连续失败达到阈值后转换为 `UNHEALTHY`。
- 连续成功达到阈值后转换为 `HEALTHY`。
- 仅在状态发生变化时记录健康状态日志，避免周期性日志噪声。
- 单个实例检查失败不能中止其他实例或后续调度。
- 关闭代理时依次停止监听、健康调度器和工作线程池；等待短暂宽限期后强制结束残留任务。

阶段出口：

- 首次检查完成前业务请求返回 `503`。
- 停止模拟后端并达到失败阈值后，该实例不再收到流量。
- 恢复模拟后端并达到成功阈值后，该实例重新参与均衡。
- 调度任务异常不会终止后续检查。
- `close()` 后不存在遗留的非守护线程。

### 阶段 5：超时、重试和访问日志

实施内容：

- 连接超时使用 `HttpClient.connectTimeout`。
- 每次转发通过 `HttpRequest.timeout` 应用单次请求超时。
- 自动重试仅适用于 `GET`、`HEAD`、`PUT`、`DELETE`、`OPTIONS`。
- `POST` 和 `PATCH` 永不自动重试。
- 以下情况允许切换到尚未尝试的健康后端：
    - 建连失败。
    - I/O 异常。
    - 单次请求超时。
    - 后端返回配置中的 `502`、`503` 或 `504`。
- 普通 `4xx` 和未配置的 `5xx` 直接返回客户端。
- 错误映射固定为：
    - 无健康后端：`503`
    - 所有尝试为连接或协议错误：`502`
    - 最终一次失败为超时：`504`
    - 请求体超限：`413`
    - 未预期内部错误：`500`
- 每个请求最终输出一条访问日志，字段包括：
    - 时间戳
    - requestId
    - 客户端地址
    - 方法
    - URI
    - 最终后端
    - 尝试次数
    - 状态码
    - 响应字节数
    - 总耗时毫秒数
    - 错误分类
- 日志不得记录 Authorization、Cookie、请求体等敏感数据。

阶段出口：

- 第一后端连接失败或超时时，幂等请求能从第二后端成功返回。
- POST/PATCH 即使失败也只尝试一次。
- 同一请求不会重复选择同一实例。
- 达到最大次数后立即停止。
- 502、503、504 和成功重试场景均有确定性测试。
- 每个请求只产生一条最终访问日志。

### 阶段 6：系统验收、文档和可执行交付物

实施内容：

- 补齐 `README.md`：
    - 环境要求
    - 构建命令
    - 启动命令
    - 配置示例
    - curl 演示
    - 测试命令
    - 已知限制
- 补齐 `docs/design.md`：
    - 架构与模块职责
    - 请求转发流程
    - URI 和请求头处理规则
    - 两种负载均衡算法
    - 后端状态机
    - 超时和重试语义
    - 并发模型
    - 启动和关闭流程
- 提供可复制的 `config/proxy-example.properties`。
- 执行完整干净构建和本地双后端验收。
- 验证 Windows 中文环境下配置、日志和文档无编码问题。

最终验收场景：

1. 启动两个本地后端 A、B，代理首次健康检查后开始转发。
2. 普通轮询呈现 A、B、A、B。
3. 权重 A=3、B=1 时长期请求比例接近 3:1。
4. 停止 A 后，达到失败阈值，全部流量进入 B。
5. 重启 A 后，达到成功阈值，A 重新加入流量池。
6. A 故意超时，GET 请求切换到 B 并成功。
7. A 对 POST 超时，代理不重试。
8. 所有后端不可用时返回 `503`。
9. 请求体超过 10 MiB 时返回 `413`。
10. `mvn clean test` 从空 `target` 开始通过，并生成可直接运行的 JAR。

## 3. 主要接口与类型

- `ProxyApplication`：CLI 入口、配置加载、组件组装和关闭钩子。
- `ProxyConfig`、`BackendConfig`：Java 17 record，不可变并在构造期完成校验。
- `BackendState`：`UNKNOWN`、`HEALTHY`、`UNHEALTHY`。
- `LoadBalancingStrategy`：`ROUND_ROBIN`、`WEIGHTED_ROUND_ROBIN`。
- `LoadBalancer`：从健康快照中选择未尝试实例。
- `BackendRegistry`：原子更新状态并提供不可变健康快照。
- `BackendProbe`：隔离健康探测，便于单元测试注入假实现。
- `UpstreamTransport`：隔离实际 `HttpClient` 调用，便于模拟连接失败、超时和状态码。
- `ReverseProxyServer`：提供 `start()`、`address()`、`close()`。
- `HealthChecker`：提供生命周期方法，并保留包级 `checkOnce()` 供确定性测试使用。

## 4. 测试计划

- 单元测试：配置解析与校验、普通轮询、平滑加权轮询、后端状态转换、重试决策、错误状态映射、URI 拼接和头过滤。
- 并发测试：多线程选择后端、健康状态并发更新、启动关闭幂等性。
- 集成测试：使用回环地址和端口 `0` 创建真实代理及模拟后端，不依赖互联网或固定端口。
- 超时测试：使用可控假传输层作为主要验证，真实慢后端只保留少量集成覆盖，避免测试依赖长时间 `sleep`。
- 生命周期测试：所有服务器、线程池和调度器均在 `try-with-resources` 或测试清理阶段关闭。
- 每个阶段结束都执行 `mvn clean test`，不能依赖旧 `target` 产物。

## 5. 已确定的假设和默认决策

- 首版只实现核心必做项，不实现四个可选模块。
- Java 17 是唯一编译基线；未来在 Java 21 上可直接运行，但不会使用虚拟线程专属 API。
- 配置修改后需要重启代理，不支持热加载。
- 请求体全量缓冲，固定默认上限 10 MiB。
- 响应在收到后端响应头后流式写出；响应中途断开不重试。
- 仅幂等请求自动重试，POST/PATCH 不重试。
- 后端必须首次探测成功后才参与流量分配。
- 每次请求使用开始时取得的健康后端快照，并排除本请求已经尝试的实例。
- 管理接口只提供 JSON 健康状态，不构成 Web 管理界面。
- 访问日志默认通过控制台输出，由运行环境负责重定向和留存。
