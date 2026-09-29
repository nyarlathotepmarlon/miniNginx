# 阶段 5：超时与有限重试

本阶段在真实健康检查、普通轮询和平滑加权轮询的基础上接入业务重试。只扩展转发故障处理，不增加限流、服务发现、热加载或整体请求截止时间；阶段 6 的最终设计文档和系统交付待审核后再实施。

## 配置与超时边界

已有配置项现在全部参与业务转发：

```properties
proxy.connect-timeout-ms=1000
proxy.request-timeout-ms=3000
retry.max-attempts=2
retry.status-codes=502,503,504
```

- `connect-timeout-ms` 用于 HttpClient 建立新连接，不作用于已经复用的连接。
- `request-timeout-ms` 为每次尝试重新设置 HttpRequest 超时，约束获得上游响应头的阶段，并非客户端请求的整体截止时间。
- 使用 `BodyHandlers.ofInputStream()`，收到响应头后开始流式转发；不承诺完整响应体、慢客户端上传/下载或工作池排队时间的上限。两次尝试可能各耗用一次请求超时预算。
- `max-attempts` 包含第一次发送；设为 1 可关闭业务重试。设置比健康实例数大，也不会重复尝试同一实例。
- 默认仅对 HTTP 502、503、504 重试；其他 4xx 和未配置的 5xx 原样返回。保持阶段 1 已支持的配置能力：可以显式填写 400–599 的错误码（例如 `429,503`）；**只有显式列入的 4xx 才会触发重试**。修改该列表应基于后端实际语义。空列表关闭按状态码重试，但不关闭连接/I/O/超时重试。

修改配置需重启。健康请求继续使用独立的 `health.timeout-ms`，不经过业务重试循环，每轮每实例仅做一次探测。

## 方法、快照与尝试次数

只有大小写精确匹配的 `GET`、`HEAD`、`PUT`、`DELETE`、`OPTIONS` 自动重试；`POST`、`PATCH` 以及其他方法永不自动重试。即使收到 503 或发生连接中断，也不会重放 POST/PATCH。

PUT 和 DELETE 的重试依赖后端正确实现幂等语义，包括多实例共享状态的一致性。连接中断不意味着后端一定没有执行请求，代理不能提供恰好执行一次的保证。可用 `max-attempts=1` 禁用自动重放。

处理流程：

1. 管理请求单独处理；业务请求从注册表获取一次不可变健康快照。UNKNOWN 和 UNHEALTHY 不参与选择。
2. 选择首个后端，缓冲并校验请求体。超过上限返回 413，不发生上游发送。
3. `RetryPolicy` 根据方法和快照计算有效上限：幂等方法为 `min(max-attempts, 不同健康实例数)`，其他方法至多一次。
4. 每次尝试重新创建目标 HttpRequest，保留各后端基准路径、原始路径/查询、方法、缓冲正文和安全请求头；所有尝试共用同一个 requestId，X-Forwarded-For 不会因重试重复追加。
5. 连接失败、其他 I/O/协议异常或等待响应头超时，且还有预算时，均衡器从初始快照中选取未尝试过的实例。
6. 合法响应的状态码在重试集合内，且存在下一实例时，先关闭被放弃的响应体，再发送下一次请求。不读取废弃正文到 EOF，也不将其响应头写入下游。关闭报告 I/O 错误时，仍可继续已经选定的下一次尝试。
7. 无需或不能重试时，把最终响应交给 ResponseWriter。响应头一旦开始写向客户端，所有后续流错误只关闭资源，绝不重新选择后端。

请求中的尝试集合和计数器是请求局部状态；均衡算法状态维持原有并发保护。两个策略都应用“已尝试 ID”排除规则。健康成员在请求途中变化不改变该请求的初始快照；新请求才使用最新状态。业务失败不直接修改健康注册表，摘除/恢复仍由周期探测阈值决定。

## 最终结果与错误映射

**最后一次取得合法 HTTP 响应时，保留该响应的状态、经安全过滤的响应头和正文。** 不把上游 503 误报为代理内部 500 或 502。

| 最终情况 | 客户端状态 | 日志错误分类 |
| --- | --- | --- |
| 开始时没有健康后端 | 503 | NO_BACKEND，attempts=0 |
| 最后一次为连接/I/O/协议错误，无可转发响应 | 502 | UPSTREAM_IO_FAILURE |
| 最后一次在获得响应头前超时，包括建连超时 | 504 | UPSTREAM_TIMEOUT |
| 请求体超过限制 | 413 | REQUEST_BODY_TOO_LARGE，attempts=0 |
| 非预期内部异常 | 500 | INTERNAL_ERROR，不继续重试 |
| 最后一次得到合法 4xx/5xx | 保留上游状态 | UPSTREAM_HTTP_ERROR |
| 重试后成功 | 保留成功响应 | NONE，attempts 为实际发送次数 |
| 响应提交后上游/客户端流中断 | 保留已提交状态并中断连接 | UPSTREAM_STREAM_FAILURE / CLIENT_STREAM_FAILURE |

例如：A=503、B=503 → 返回 B 的 503 与正文；A=超时、B=连接失败 → 502；A=连接失败、B=超时 → 504；A=连接失败、B=404 → 原样返回 B 的 404。

不把组件内部抛出的 IllegalArgumentException 误归类为客户端 400。客户端路径/请求构建错误在对应输入处理处单独转换为 400；不支持的协议仍为 501。

关闭和线程中断不触发新的尝试。中断时停止处理并保留线程中断标记；若尚未提交 HTTP 响应，日志状态为 0，代表未发送状态行，而不是一个 HTTP 状态码。

## JDK 内部重试与资源所有权

保留此前通过本机 JDK 17 源码和独立 JVM 故障注入验证的设置，在首个 HttpClient 初始化前应用：

```text
jdk.httpclient.disableRetryConnect=true
jdk.httpclient.enableAllMethodRetry=false
jdk.httpclient.redirects.retrylimit=1
```

`disableRetryConnect` 单独使用不能覆盖 JDK 17 的全部连接重试路径；还需限制内部发送上限并禁止对所有方法的额外重试。HttpClient 不跟随重定向。独立 JVM 测试会故意传入相反的启动属性，再验证应用设置后 GET/POST/PATCH 在断开连接的后端均只有一次真实请求。

一次业务尝试定义为一次 `UpstreamTransport.send()`，不等价于 TCP 数据包、DNS 查询或探测次数。应用重试在不同后端间执行，不能让 JDK 在同一后端偷偷重放。作为库嵌入已有 JVM 时，宿主必须在首次 HttpClient 使用前设置这些 JVM 全局参数，不能假定晚设置可以覆盖 JDK 已缓存的配置。

每次已取得的 UpstreamResponse 都由 try-with-resources 关闭；废弃响应、最终响应、协议错误和选择器异常均清理正文。活动响应保持在关闭跟踪集合内直到清理结束。健康检查与服务器/线程池生命周期沿用[阶段 4](stage-4.md)。

## 最终访问日志

每个业务或管理请求只输出一条最终事件，失败尝试不另外生成访问日志。字段包含 UTF-8 时间戳、requestId、客户端、方法、原始 path、最终后端 ID、实际尝试次数、最终状态、成功写出的正文字节数、总耗时毫秒和错误分类。

```text
timestamp=... requestId="..." client="127.0.0.1" method="GET" path="/demo" backend="api-b" attempts=2 status=200 responseBytes=2 durationMs=... error="NONE"
```

总耗时从处理器进入到清理完成，包含请求体缓冲、各次尝试、废弃响应清理和最终流式写入，但不包含进入工作线程之前的排队。字节数不含响应头、分块编码和未传出的废弃正文；HEAD 等无正文响应为 0。

未发生上游发送时（如 413、无健康实例、管理请求），backend 为 `-` 且 attempts=0，不把仅选择过但未发送的实例记作最终后端。

沿用隐私边界：不记录 Authorization、Cookie、正文、上游异常原文，也不记录可能包含令牌的查询串；日志中的 URI 信息仅为原始 path。因此合法上游错误与代理传输错误可区分，但日志不暴露原始故障内容。

## 验证与运行

在 Java 17 / Windows PowerShell 下：

```powershell
mvn clean test
mvn package
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\proxy.properties
```

定向测试（命令行参数在 PowerShell 中加引号）：

```powershell
mvn "-Dtest=RequestForwarderRetryTest,RetryPolicyTest,RetryIntegrationTest,JdkHttpTransportTest" test
```

- 内存传输与交换测试覆盖方法×故障矩阵、状态码重试、最终结果映射、最大次数、不同实例排除、请求中健康变化、正文重放、原始编码、相同 requestId、清理失败、内部异常、关闭/中断、并发请求隔离，以及单条最终日志与敏感信息保护。
- 真实回环服务器测试统计已到达上游的请求，验证断开连接后的 GET 切换与 POST/PATCH 不重放；健康正常但业务缓慢的实例触发单次超时，幂等请求才切换到 B。
- 流式测试在收到首块后越过响应头超时，再释放剩余正文，验证不承诺整体正文截止时间；另外验证中途截断不会重试，也不会发送成功的终止 chunk。
- 所有回环服务器使用动态端口；主要故障测试不依赖真实长时间 sleep，不访问互联网。健康、均衡、URI、报文和生命周期测试全量回归。

健康模拟后端会显式消费空请求体以标记 EOF，避免 JVM 全局 `sun.net.httpserver.drainAmount=0` 使服务器静默关闭标为可复用的连接，意外触发重试并干扰精确均衡序列。生产代理的本地 JSON 错误及管理响应统一声明 `Connection: close`：这些入口可能保留未读请求体，不等待上传，也不让客户端误复用连接。业务正常转发的连接复用不受影响。

构建后可继续运行真实独立 JAR 双后端故障恢复检查（两种策略）：

```powershell
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar round-robin
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar weighted-round-robin
```

这些检查验证 UTF-8 中文配置/日志、正常分配、摘除恢复及全不可用的 503；阶段 5 的故障与重试次数由上述确定性测试和真实 HTTP 集成测试单独覆盖。固定工作池仍受慢上游、慢客户端占用，不将本项目宣称为生产级网关。

## 本阶段验收记录

2026-09-29，在 Windows 11、Temurin 17.0.19、Maven 3.9.16、zh_CN / UTF-8 环境完成：

- `mvn clean test`：258 项测试全部通过，0 失败、0 错误、0 跳过。相对阶段 4 新增 94 项重试策略、转发及真实故障用例；两种策略的真实正常分配检查分别扩大为 400 次请求。
- 紧接着执行 `mvn -DskipTests package`：成功生成 `target/jdk-reverse-proxy-0.1.0-SNAPSHOT.jar`；此步仅避免重复执行刚通过的测试，并未跳过前面的干净测试。
- JAR 的 `--help`、`--version` 正常，中文帮助未出现乱码。
- 两种策略的独立 JAR 检查均输出 `JAR_SMOKE_OK`：轮询 A/B 交替，加权 A/A/B/A，A 停止后仅 B 接收请求，同端口恢复后重新参与分配，全部停止后返回 503；中文配置、状态日志和访问日志检查通过。
- `git diff --check` 通过。阶段 5 已完成验收，阶段 6 尚未实施。
