# 简易 HTTP 反向代理与负载均衡器设计

本文描述阶段 6 交付版本的实际实现。构建、启动、全部配置项与 curl 演示见 [README](../README.md)，可重复执行的系统验收见 [阶段 6 验收记录](stage-6.md)。阶段 2–5 文档保留各阶段当时的设计与验证结果。

## 1. 目标与边界

以 Java 17 原生 API 实现可解释、可测试的小型反向代理：HTTP 转发、多后端、普通轮询、平滑加权轮询、主动健康检查、故障摘除与恢复、有限重试、日志及 CLI。运行时无第三方依赖，JUnit 5 仅用于测试。

- 入站为 JDK `HttpServer`，仅提供 HTTP；出站为 `HttpClient`，使用 HTTP/1.1，可连接 HTTP/HTTPS 后端。
- HTTPS 后端使用 JDK 默认信任与主机名校验，不提供忽略证书错误的选项。
- 固定工作线程处理阻塞式 I/O，不使用 Java 21 虚拟线程。
- 不包含限流、Web 管理页面、Docker、HTTPS 终止、WebSocket、CONNECT 隧道、热加载和服务发现。
- 定位为学习、演示和面试项目，不承诺生产网关级别的性能、完整协议兼容性或请求恰好执行一次。

## 2. 架构与模块职责

```text
ProxyApplication ── ConfigLoader / ProxyConfig
        │
        └── ReverseProxyServer
             ├── HttpServer → 固定工作池 → RequestForwarder
             │                              ├── BackendRegistry 健康快照
             │                              ├── LoadBalancer / RetryPolicy
             │                              ├── RequestBodyBuffer / TargetUri / HeaderFilter
             │                              └── UpstreamTransport → 后端 → ResponseWriter
             └── HealthChecker → 独立调度池 → BackendProbe
                                                └── 更新 BackendRegistry
业务请求结束 → AccessLogger；健康状态改变 → HealthLogger
```

| 模块 | 职责与边界 |
| --- | --- |
| `ProxyApplication`、`CliOptions` | CLI 解析、配置加载、启动输出、组件装配、关闭钩子与退出码 |
| `config` | UTF-8 Properties 解析、默认值、不可变 `ProxyConfig` / `BackendConfig` 与启动校验 |
| `backend` | 按配置顺序维护后端状态，原子更新计数器，提供不可变 `BackendSnapshot` 与健康候选列表 |
| `balance` | `LoadBalancer` 统一选择接口及两种线程安全算法，不负责网络和健康探测 |
| `proxy` | 请求校验、管理路径分流、正文缓冲、URI/头过滤、重试编排与响应写出 |
| `transport` | `UpstreamTransport` 隔离网络调用；`JdkHttpTransport` 执行一次上游交换 |
| `health` | `BackendProbe` 隔离探测；`HealthChecker` 负责独立调度、阈值状态转换 |
| `logging`、`lifecycle` | 最终访问事件、状态转换日志以及有界等待的线程池关闭 |

`ReverseProxyServer.create(config)` 是正式启动入口，始终启用真实健康检查。接收 `BackendPool`、`BackendProbe`、`UpstreamTransport`、`LoadBalancer` 的重载用于测试注入；`StaticBackendPool`、`SingleBackendSelector` 不参与默认 CLI 的生产装配。

## 3. 配置模型与启动校验

配置通过 `Files.newBufferedReader(path, UTF_8)` 交给 `Properties.load(Reader)`，文件应保存为 UTF-8 无 BOM；中文 ID 无需手写 Unicode 转义。配置加载完成后转换为 Java 17 record，列表和集合采用防御性复制。配置变更必须重启。

格式采用分组键，不依赖框架：

```properties
backends=api-a,api-b
backend.api-a.url=http://127.0.0.1:9001/api
backend.api-a.weight=3
backend.api-a.health-path=/health
backend.api-b.url=http://127.0.0.1:9002/api
load-balancer.strategy=weighted-round-robin
```

其余设置采用默认值。完整可复制文件为 [proxy-example.properties](../config/proxy-example.properties)，默认值表见 [README 配置说明](../README.md#5-cli-与配置)。上述 `/api` 例子只是路径规则示例，与默认回显后端的根路径配置不同。

校验要点：

- 必须有至少一个后端，ID 唯一、非空且不包含逗号或空白。
- 后端 URI 只能为 HTTP/HTTPS，必须包含 host，不含用户凭据、query、fragment；显式端口须合法。
- 正式 Properties 的监听端口为 1–65535；直接构造配置可用端口 0，供回环集成测试绑定临时端口，不放宽正式文件校验。
- 工作线程数、权重、各超时、周期、阈值和最大尝试次数必须为正。正文上限不超过 Java 数组限制 `2147483639` 字节；配置合法不等于内存一定足够。
- 健康路径和管理前缀必须为以 `/` 开头、不带 authority/query/fragment 的 URI 路径；管理前缀不能为根路径或以 `/` 结尾。
- `retry.status-codes` 默认 `502,503,504`，可显式配置 400–599；空列表仅关闭按状态码重试。`retry.max-attempts=1` 才会关闭全部业务自动重试。

默认监听 `127.0.0.1:8080`；默认工作线程为 `max(8, CPU 核数 × 2)`，示例文件显式指定 16。URI、数值等校验错误在启动期报告，不等到首次转发才暴露。

## 4. 单次请求流程

1. 工作线程开始处理时生成 UUID requestId，记录开始时间和客户端 IP，注册活动响应写入器。
2. 拒绝 CONNECT/Upgrade 请求（501）及不支持的请求目标（400）；识别管理前缀，管理请求不消耗负载均衡序列。
3. 业务请求获取一次不可变健康快照。计算本请求尝试上限，选择第一个后端；没有候选时直接返回 503。
4. 将客户端正文缓冲到内存。声明长度已超限时不等待上传就返回 413；流式读取时也累计限制，防止未知长度正文绕过检查。
5. 为选中后端构造目标 URI，复制安全请求头，使用同一份缓冲正文建立新的 `HttpRequest`。发送前记录后端 ID 和实际尝试次数。
6. 调用 `UpstreamTransport.send()`。可重试故障或状态码出现时，仅在尚未提交下游响应、预算未耗尽且有未尝试后端的情况下切换。
7. 接受最终上游响应，过滤响应头，提交状态行与响应头，随后逐块转发正文。此后流错误只能关闭连接，不能再次选择后端或发送另一份错误响应。
8. 关闭上游响应体和下游 exchange，注销活动资源，并输出一条最终访问事件。

正文限制发生在实际发送之前，但后端快照与首次选择在正文读取之前。因此无健康后端时优先返回 503；超限请求不会发送给后端，但可能已推进一次调度位置。

## 5. URI、请求头与响应写出

### 5.1 原始 URI 拼接

`TargetUri` 使用 `getRawPath()` / `getRawQuery()`，拼接 scheme、authority、后端路径前缀、客户端路径和查询，不使用会替换基准路径的 `URI.resolve()`，也不做先解码再编码。

| 后端基准路径 | 客户端目标 | 转发路径与查询 |
| --- | --- | --- |
| `/api` | `/users` | `/api/users` |
| `/api/` | `/users` | `/api/users` |
| `/api` | `/files/a%2Fb` | `/api/files/a%2Fb` |
| `/api` | `/search?q=a%2Bb` | `/api/search?q=a%2Bb` |
| `/api` | `/a/../b` | `/api/a/../b` |
| `/api` | `//files/a` | `/api//files/a` |

后端前缀末尾有 `/` 时仅移除拼接边界的一条斜杠，其余双斜杠、点路径和百分号编码原样保留。只接受 origin-form；`//files/a` 在 Java URI 中被解析出的 authority 会还原成路径，不允许客户端借此替换配置中的后端 host。

### 5.2 双向头过滤

`HeaderFilter` 分方向处理请求头和响应头，头名称比较不区分大小写：

- 固定过滤 `Connection`、`Keep-Alive`、`Proxy-Connection`、`Proxy-Authenticate`、`Proxy-Authorization`、`TE`、`Trailer`、`Transfer-Encoding`、`Upgrade`。
- 解析该方向所有 `Connection` 值中的逗号分隔 token，同时过滤其动态声明的字段。
- 请求不复制 Host、Content-Length、Expect，以及客户端自带的转发元数据字段；Host 和正文分帧由 JDK HTTP 客户端生成。
- X-Forwarded-For 在未被 Connection 排除时追加客户端 IP；X-Forwarded-Host 使用原始 Host，X-Forwarded-Proto 固定为 `http`，X-Request-Id 使用新生成的 UUID。每次重试均从原始请求重新构造，避免重复追加。
- 响应不复制上游 Content-Length、X-Request-Id；输出代理自身 requestId。安全响应头（包括多值 Set-Cookie、Location）保留，不重写 Location。
- 两个方向均追加 `Via: 1.1 jdk-reverse-proxy`，不跟随上游重定向。

Authorization/Cookie 等业务头在未被逐跳规则排除时仍会转发，但绝不写入访问日志。客户端原有 X-Forwarded-For 前缀不视为可信身份，部署者需自行建立可信代理链策略。

### 5.3 响应体与提交边界

`ResponseWriter` 使用 `HttpExchange.sendResponseHeaders()`：

| 场景 | 长度参数 | 处理 |
| --- | --- | --- |
| HEAD、204、205、304 | `-1` | 不发送正文，并关闭上游流 |
| 普通上游响应 | `0` | 分块传输；每次读取最多 8 KiB，写出并刷新 |
| 本地错误 JSON / 健康 JSON | 正文实际字节数 | 固定长度；HEAD 仍不发送正文 |

响应一旦开始提交便禁止重试。上游读取失败记 `UPSTREAM_STREAM_FAILURE`，下游写入失败记 `CLIENT_STREAM_FAILURE`；状态保持已提交值，日志只统计成功写出的正文。在中断分块响应时，写出包装层阻止 JDK 自动补一个“正常完成”的结束块，避免截断内容伪装成完整响应。

本地生成的 JSON 声明 `Connection: close`，以免正文未读完时错误复用连接。服务器在首次初始化前设置 JDK 专用属性 `sun.net.httpserver.drainAmount=0`，使拒绝超大或不支持的请求时不为丢弃剩余上传而长时间阻塞。这是 JVM 级设置，嵌入其他应用时需注意影响范围。

## 6. 负载均衡算法

统一接口为 `select(健康快照, 已尝试后端 ID 集合)`，返回未尝试实例或空结果。算法再次过滤非 HEALTHY 项；业务调用方仅把每次发送前确认的后端加入本请求排除集合。

### 普通轮询

使用 `AtomicInteger.getAndIncrement()` 获取选择位置，再通过 `Math.floorMod(index, eligibleSize)` 处理有符号整数溢出。针对过滤健康状态和排除集合后的候选列表选择；空列表返回空而不推进计数器。稳定双实例下产生 `A, B, A, B`。健康集合或请求排除集合改变后，位置仍按当前候选列表取模，不保证延续旧列表中下一实例的身份。

### 平滑加权轮询

`SmoothWeightedRoundRobinLoadBalancer` 用一个同步锁保护实例表和累计权重，权重及总权重累加使用 `long`：

1. 每个可选实例的 `currentWeight += weight`。
2. 选择 currentWeight 最大者；相等时按算法状态初始化时的实例顺序决定。
3. 选中实例的 `currentWeight -= 本次可选实例的总权重`。

权重 A=3、B=1、累计值均为零时，序列为 `A, A, B, A`。无故障的完整周期内比例精确为 3:1；重试和健康变更会影响短期序列，这不是业务成功请求数的固定配额。

只有健康成员、实例 ID、URI 或权重发生实际变化才重建状态。新建的快照对象、探测计数变化、列表顺序变化不会重置权重；请求级排除也不算上下线。被排除实例不参与本次加权累计和总权重计算，其累计值暂时冻结。

每个请求始终使用初始快照，即使期间有实例摘除或恢复也不换快照。这使重试预算明确，但意味着进行中的请求仍可能尝试刚摘除的实例；之后的新请求使用新快照。

## 7. 健康状态机与管理接口

注册表以配置顺序保存实例，状态读取、成功/失败计数更新及快照生成均在同步保护下完成。快照为不可变值，离开锁后由调度/业务线程各自使用，不持有注册表锁进行网络 I/O。

| 当前状态 | 探测结果与阈值 | 新状态 |
| --- | --- | --- |
| UNKNOWN | 连续成功达到 success-threshold | HEALTHY |
| UNKNOWN | 连续失败达到 failure-threshold | UNHEALTHY |
| HEALTHY | 连续失败未达阈值 | HEALTHY |
| HEALTHY | 连续失败达到阈值 | UNHEALTHY |
| UNHEALTHY | 连续成功未达阈值 | UNHEALTHY |
| UNHEALTHY | 连续成功达到阈值 | HEALTHY |

成功会清零失败计数，失败会清零成功计数；计数到阈值后封顶。UNKNOWN 不接收流量。默认 success-threshold=1，首次成功即可加入；设为 2 时，首次成功仍为 UNKNOWN。

`HealthChecker` 启动时立即为每个实例提交固定周期任务；`HttpBackendProbe` 发送 GET，目标为后端基准 URI 与 health-path 的原始路径拼接，使用独立 health.timeout-ms。200–399 成功，连接/I/O/超时、其他状态及探测运行时异常失败；探测不使用业务重试。收到响应头后即关闭探测响应体，无须下载完整正文。

只有状态真的变化才记录健康日志。单次检查或日志回调的运行时异常被隔离，不使后续周期永久取消；JVM 致命 Error 不作为普通健康故障吞掉。业务请求的超时、5xx 不直接改变健康状态，因此健康接口必须能代表后端实际可用性。

管理路径先进行严格边界判断：等于前缀或以 `前缀 + /` 开头才保留。默认行为：

- `GET /_proxy/health`：至少一个 HEALTHY 返回 200，否则返回 503；JSON 包含 UP/DOWN、运行模式、是否启用探测、策略、配置后端总数及健康/候选数。
- 精确健康路径的非 GET 返回 405；其他管理路径返回 404，不转发后端。
- `/_proxy-other` 是普通业务路径；管理判断使用原始路径，不做百分号解码后的二次匹配。

管理接口无认证，和业务共用监听端口及工作池；默认绑定回环地址，对外开放需要部署层访问控制。

## 8. 超时、重试与错误语义

### 超时边界

`HttpClient.connectTimeout` 限制新建连接，不作用于复用的连接。每次尝试独立使用 `HttpRequest.timeout`。由于响应处理器为 `BodyHandlers.ofInputStream()`，拿到响应头即可返回，不能据此承诺响应体也在配置时间内读完。

请求排队、客户端上传、完整流式响应体及慢客户端下载没有统一的端到端截止时间。两次尝试可以各耗用一次 request-timeout-ms；本版没有总预算、指数退避或熔断器。

### 重试决策

- 仅精确匹配的 GET、HEAD、PUT、DELETE、OPTIONS 可自动重试；POST/PATCH 及其他方法至多尝试一次。
- 有效上限为 `min(retry.max-attempts, 初始快照内不同健康后端 ID 数)`，包含首次；同一请求不重复选择同一 ID。
- 连接/I/O/协议失败、单次超时，或显式配置的 HTTP 错误状态码，才能触发切换。
- 尚有次数、未提交下游响应、存在未尝试后端、线程未中断且代理未关闭，才允许重试。终止/关闭不继续发起新请求。
- 放弃 HTTP 响应前必须关闭它的 InputStream，不先读取剩余正文，也不把其头或正文写给客户端。重放使用同一份缓冲请求体和 requestId。
- PUT/DELETE 的重放依赖后端正确实现跨实例幂等性。连接断开时原实例可能已经执行，代理不提供恰好一次保证。

### 最终结果

| 最终观察结果 | 状态 / 错误分类 |
| --- | --- |
| 开始时无健康候选 | 503 / NO_BACKEND，attempts=0 |
| 请求正文超限 | 413 / REQUEST_BODY_TOO_LARGE，attempts=0 |
| 客户端正文读取错误 / 请求目标非法 | 400 / CLIENT_REQUEST_FAILURE 或 INVALID_REQUEST |
| 最后一次未取得响应，连接/I/O/协议错误 | 502 / UPSTREAM_IO_FAILURE |
| 最后一次未取得响应，等待超时 | 504 / UPSTREAM_TIMEOUT |
| 最后一次取得合法 HTTP 响应 | 原样返回；4xx/5xx 记 UPSTREAM_HTTP_ERROR |
| 未预期内部运行时异常，尚未提交响应 | 500 / INTERNAL_ERROR |
| 已提交后流中断 | 中断连接，保留已提交状态，不改写为 502/504 |

例如 A 返回 503，B 也返回 503，则保留 B 的状态、头和正文；A 超时后 B 连接失败，最终为 502；A 连接失败后 B 超时，最终为 504。成功重试的最终事件为 NONE，并记录最终后端和实际尝试次数。

### 禁用 JDK 隐式重放

`JdkHttpTransport` 在首次 HttpClient 交换前设置：

```properties
jdk.httpclient.disableRetryConnect=true
jdk.httpclient.enableAllMethodRetry=false
jdk.httpclient.redirects.retrylimit=1
```

仅禁用连接重试不足以覆盖 JDK 17 的失效连接重放路径，因此同时限制其内部尝试。客户端还明确设置不使用系统代理、HTTP/1.1 和 `Redirect.NEVER`。独立子 JVM 故障注入测试检查 GET、POST、PATCH 的真实线上请求计数，不以 send() 的调用次数替代网络证据。

这些属性由 JDK 缓存且作用于整个 JVM；作为库嵌入时，必须由宿主在第一次 HttpClient 使用前设置。这里的行为由当前 JDK 17 测试验证，升级运行时需重跑相关测试，不能视为跨任意 JDK 的协议保证。

## 9. 并发容量与资源所有权

- 业务池：固定 N 个非守护工作线程，命名为 `proxy-<id>-worker-<n>`；`ArrayBlockingQueue` 容量 2N，监听 backlog 也传入 2N。
- 过载采用拒绝策略，让 HttpServer 关闭无法调度的连接，不在其分发线程执行阻塞 I/O；不保证返回 HTTP 503。backlog 与操作系统共同作用，不构成严格的客户端连接总数承诺。
- 健康池：独立 `ScheduledThreadPoolExecutor`，线程数 `min(max(1, 后端数), 4)`，非守护线程 `proxy-health-<id>-<n>`。同一实例的固定周期任务不会重叠，耗时过长可能导致探测延后或紧接着再次执行。
- 工作池忙时探测仍可推进，但管理 HTTP 请求共享工作池，可能同样延迟。持续慢请求资源测试验证有界业务并发和独立健康探测，而不是吞吐量基准测试。
- 请求体每请求最多默认 10 MiB，缓冲过程和重放有额外内存开销；配置上限乘并发只是估算起点，不是准确堆容量上界。没有磁盘溢写或大文件流式上传。
- 每个被接受的 `UpstreamResponse` 由转发器关闭；被丢弃、无正文、正常结束和异常结束均覆盖。活动上下游资源还由 RequestForwarder 的线程安全集合跟踪，用于关闭时解除阻塞。
- Java 17 HttpClient 没有 AutoCloseable API，其内部连接池及守护线程由 JDK 管理；本项目显式关闭响应流和自建线程池，不宣称主动销毁所有 JDK 内部线程。

## 10. 启动与关闭

启动顺序：解析 CLI → 用 UTF-8 读配置并校验 → 创建 transport/注册表/均衡器/健康检查器 → `start()` 绑定监听地址、设置上下文与工作池 → 启动健康调度 → 输出启动信息并等待终止。

CLI 支持 `--config <path>`、`--help`/`-h`、`--version`/`-V`。无参数读取当前工作目录下的 `config/proxy.properties`；缺失/非法配置或绑定失败以非零状态退出，不悄悄回退到其他配置。JAR Manifest 指向 `com.example.proxy.ProxyApplication`。

`create()` 不提前绑定端口，防止 JDK 17 / Windows 中“已绑定但未启动”的 HttpServer 释放不完整。`start()` 和 `close()` 同步保护：重复启动不重复分配资源，重复关闭安全；关闭后不允许重新启动，需要新建服务器对象。

正常关闭通过 JVM shutdown hook 或 try-with-resources 调用，顺序为：

1. 停止监听，允许在途 exchange 至多短暂宽限（已启动时 1 秒）。
2. 停止健康调度，标记关闭；关闭后迟到的探测结果不再更新状态或记录转换日志。
3. 关闭活动下游写入器和上游响应流，解除阻塞读取。
4. 关闭业务池。调度池和业务池各先等待 1 秒，再 shutdownNow，并最多等待 3 秒。
5. 释放终止信号，唤醒入口等待。各清理步骤用 finally 衔接，尽量避免某一步失败跳过后续资源。

中断标志在清理后恢复；线程池超出强制等待时间仍未终止则明确报告异常。测试注入的 probe/transport 必须响应中断或资源关闭，Java 不能安全强杀任意不合作线程。生命周期测试检查本项目拥有的非守护线程不遗留，并覆盖并发/重复关闭、未启动即关闭和启动失败。

## 11. 日志与可观测性

JUL 的 UTF-8 ConsoleHandler 输出到 stderr，启动信息输出到 stdout。日志留存与轮转交给运行环境；旧版 PowerShell 的重定向可能重新编码，读取时明确使用 UTF-8。

每个进入处理器的请求在 finally 中输出一条最终事件，管理请求亦然：

```text
timestamp=2026-09-29T03:00:00Z requestId="uuid" client="127.0.0.1" method="GET" path="/demo" backend="api-b" attempts=2 status=200 responseBytes=2 durationMs=1004 error="NONE"
```

时间戳为 UTC Instant；耗时从工作线程开始执行到处理结束，不包含排队。responseBytes 只计已成功写出的正文，不含头、分块分帧和被放弃响应；没有上游发送时 backend 为 `-`、attempts 为 0；中断且未发出状态行时 status 可以为 0。过载时根本未进入处理器的连接不保证产生访问事件。

只记录原始 path，不记录查询串、Authorization、Cookie、正文或原始异常消息；字符串对控制字符转义，避免日志注入。路径本身仍可能含业务标识，部署者须自行确定日志访问与保留策略。健康日志只在状态转换时输出，不按每个周期刷屏。

## 12. 验证策略与交付

确定性单元测试注入传输或探测故障，主要验证状态机、尝试预算、不同后端排除、最终状态及资源关闭；算法测试覆盖溢出、长期分布、真实成员变化与并发选择。回环真实网络测试使用动态端口，覆盖常见方法、二进制正文、双向逐跳头过滤、URI、健康恢复、流中断及实际请求重放。

`mvn clean test` 清除旧产物并从源码运行测试；随后 `mvn "-DskipTests" package` 仅打包刚验证的代码，或直接用 `mvn clean package` 一次完成。独立 `JarSmokeCheck` 再启动最终 JAR，验证两种策略及十项最终场景，并留下 UTF-8 配置、访问日志和结果文件。它不依赖 JUnit，也不包含在默认 Surefire 的测试计数中。

验收范围、实际命令、环境与结果见 [阶段 6 系统验收](stage-6.md)。剩余限制是首版明确边界，不以单次本地测试推导生产性能或完整 HTTP 兼容性。
