# 简易 HTTP 反向代理与负载均衡器

基于 Java 17 和 JDK 原生 HTTP API 的轻量反向代理，用于学习、演示和后端开发实践。代理接收客户端请求，根据健康状态和负载均衡策略选择后端，并提供主动健康检查、有限重试与访问日志。

运行时不依赖 Spring、Netty 等第三方框架：入站使用 `HttpServer`，出站使用 `HttpClient`，日志使用 `java.util.logging`；Maven 负责构建，JUnit 5 用于测试。

文档入口：[架构与设计](docs/design.md) · [阶段 6 系统验收](docs/stage-6.md) · [可复制配置模板](config/proxy-example.properties)。本文件说明最终版本的构建、运行与使用方式。

## 1. 功能概览

- HTTP 请求与响应转发，保留方法、原始路径、查询串、安全报文头及二进制正文。
- 多后端普通轮询与平滑加权轮询。
- 主动健康检查，支持连续失败摘除、连续成功恢复。
- 建连超时、单次等待响应头超时，以及仅针对指定幂等方法的有限重试。
- 请求体大小限制，默认 10 MiB；响应采用流式转发。
- Properties 配置、CLI 启动、JSON 健康接口、UTF-8 访问与健康状态日志。
- 配置、算法、并发、转发、健康状态、重试及生命周期的自动化测试。

监听端仅支持 HTTP，后端地址支持 HTTP/HTTPS。本项目不定位为生产级网关，功能边界见文末“已知限制”。

## 2. 环境要求

- JDK 17：编译目标为 `maven.compiler.release=17`，不使用虚拟线程。
- Maven：本地验收使用 3.9.16。仓库未提供 Maven Wrapper，需要将 `mvn` 加入 PATH。
- Windows PowerShell：本文示例以 Windows 为准；curl 命令明确使用 `curl.exe`，避免与 PowerShell 的同名别名混淆。
- 源码、配置和文档使用 UTF-8；保存自定义 Properties 文件时使用 UTF-8 无 BOM。

在项目根目录检查环境：

```powershell
java -version
mvn -version
curl.exe --version
```

确认 `mvn -version` 显示的 Java 版本也是 17。若与 `java -version` 不一致，检查 `JAVA_HOME` 和 PATH。

项目通过 `.mvn/jvm.config` 为 Maven 设置 UTF-8，CLI 与控制台日志也显式使用 UTF-8。若终端中文显示异常，可在当前 PowerShell 会话中设置：

```powershell
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
```

## 3. 快速开始

以下命令均在项目根目录执行。演示需要四个终端：后端 A、后端 B、代理，以及发送请求的终端。先确认本机 8080、9001、9002 端口未被占用。

### 3.1 干净构建

```powershell
mvn clean package
```

该命令清理旧 `target`，从源码编译、运行测试并生成可执行文件：

```text
target/jdk-reverse-proxy-0.1.0-SNAPSHOT.jar
```

首次构建可能需要下载 Maven 插件和 JUnit 依赖；测试本身不访问互联网，也不要求预先启动后端。

### 3.2 启动两个演示后端

终端 A：

```powershell
java .\examples\EchoBackend.java 9001
```

终端 B：

```powershell
java .\examples\EchoBackend.java 9002
```

`EchoBackend` 使用 Java 源文件运行模式，无需手动编译。它返回请求方法、URI、正文以及 `backend=9001` 或 `backend=9002`，并通过 `X-Demo-Backend` 响应头标识实例。其 `/health` 路径也返回 200，可供代理探测；它只是本地回显示例，不是生产后端。

### 3.3 启动代理

终端 C：

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\proxy.properties
```

默认配置监听 `127.0.0.1:8080`，使用普通轮询，后端为 9001 和 9002。省略参数时读取当前工作目录下的 `config/proxy.properties`，不是 JAR 文件所在目录：

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar
```

后端初始状态为 `UNKNOWN`。启动后立即探测，达到成功阈值后才接收业务流量；在此之前返回 503 是预期行为。

### 3.4 检查状态并发送请求

终端 D 先检查健康接口：

```powershell
curl.exe -i "http://127.0.0.1:8080/_proxy/health"
```

两个后端均健康时，状态码为 200，JSON 示例为：

```json
{"status":"UP","mode":"active-health-checks","healthChecksEnabled":true,"loadBalancingStrategy":"round-robin","totalBackends":2,"healthyBackends":2,"eligibleBackends":2}
```

确认 `healthyBackends` 为 2 后，连续发送业务请求：

```powershell
1..8 | ForEach-Object { curl.exe --silent --show-error "http://127.0.0.1:8080/demo" }
```

无故障、无其他业务流量时，可看到后端交替出现。刚启动且尚未发送业务请求时，序列为 `9001 → 9002 → 9001 → 9002`；已有请求会推进轮询位置。健康探测和管理请求不消耗业务均衡序列。

其他 curl 示例：

```powershell
# 保留编码后的路径与查询参数
curl.exe --path-as-is -i "http://127.0.0.1:8080/files/a%2Fb?q=a%2Bb"

# POST 正文，不自动重试
curl.exe -i -H "Content-Type: text/plain; charset=utf-8" --data-binary "hello from proxy" "http://127.0.0.1:8080/echo"

# PUT 正文，允许在满足条件时重试
curl.exe -i -X PUT --data-binary "updated value" "http://127.0.0.1:8080/items/1"

# HEAD 与 OPTIONS
curl.exe -I "http://127.0.0.1:8080/demo"
curl.exe -i -X OPTIONS "http://127.0.0.1:8080/demo"
```

在对应终端按 `Ctrl+C` 停止进程。代理通过关闭钩子停止监听、健康调度和工作线程，并清理活动响应资源。

## 4. 负载均衡与故障恢复演示

### 平滑加权轮询

保留两个后端运行，先停止原代理，再使用已有加权配置启动，避免重复占用 8080：

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\proxy-weighted.properties
```

此配置为 `api-a:api-b = 3:1`。等待两个后端均健康后，再执行前面的 8 次请求。全新调度状态下的四次周期为 `9001 → 9001 → 9002 → 9001`，长期比例为 3:1。权重仅影响加权策略，不影响普通轮询。

### 自动摘除与恢复

1. 在后端 A 的终端按 `Ctrl+C`，保持 B 与代理运行。
2. 检查 `/_proxy/health`，等待 `healthyBackends` 变为 1；之后的新请求只会进入 B。
3. 在原终端重新执行 `java .\examples\EchoBackend.java 9001`，等待健康数量恢复为 2；A 会重新参与均衡。
4. 停止两个后端并等待探测完成，健康接口和业务请求都返回 503。

默认每 5 秒检查一次，连续 2 次失败才摘除，不保证停机后立即更新状态。摘除前，幂等请求可能通过重试访问 B；POST/PATCH 不重试，因此仍可能失败。判断摘除与恢复是否完成，应以健康接口及状态转换日志为准。

演示后端没有人为延迟或故障注入参数。下文的 `JarSmokeCheck` 会自动启动专用故障后端，验证 GET 超时切换和 POST 不重试；`RetryIntegrationTest` 还覆盖 PATCH、断连接及真实发送次数。

## 5. CLI 与配置

### CLI 用法

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --help
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --version
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config ".\config\我的代理.properties"
```

最后一条命令要求先创建对应的 UTF-8 配置文件。路径含空格时也需加引号；`-h`、`-V` 分别是帮助、版本选项的简写。

CLI 不提供 `--port`、`--backend` 等覆盖参数，监听地址、策略和后端均在 Properties 中设置。配置文件不存在、配置非法或端口绑定失败时，会打印明确错误并以非零状态退出。修改配置后需重启代理。

### 完整配置示例

可直接使用 [普通轮询配置](config/proxy.properties) 或 [平滑加权轮询配置](config/proxy-weighted.properties)。[带中文注释的配置模板](config/proxy-example.properties) 与默认示例的配置值一致，可复制到一个尚不存在的新文件后修改：

```powershell
Copy-Item .\config\proxy-example.properties .\config\my-proxy.properties
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\my-proxy.properties
```

若已有代理占用相同端口，先停止它再启动新配置。完整配置如下：

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

### 配置项与默认值

下表是“省略该配置项”时的默认值；例如示例文件显式配置了 16 个工作线程，但代码默认值由 CPU 核数决定。

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `listen.host` | `127.0.0.1` | 监听地址，默认仅本机可访问 |
| `listen.port` | `8080` | 正式配置端口范围为 1–65535 |
| `proxy.worker-threads` | `max(8, CPU 核数 × 2)` | 固定业务工作线程数 |
| `load-balancer.strategy` | `round-robin` | 另一选项为 `weighted-round-robin` |
| `backends` | 必填 | 逗号分隔的后端 ID，至少一个且不重复 |
| `backend.<id>.url` | 必填 | 对应后端的 HTTP/HTTPS 基准 URI |
| `backend.<id>.weight` | `1` | 正整数，加权策略使用 |
| `backend.<id>.health-path` | `/health` | 追加到后端基准路径后的探测路径 |
| `health.interval-ms` | `5000` | 健康检查周期，毫秒 |
| `health.timeout-ms` | `1000` | 单次健康请求等待响应头的超时，毫秒 |
| `health.failure-threshold` | `2` | 连续失败达到此次数后摘除 |
| `health.success-threshold` | `1` | 连续成功达到此次数后加入或恢复 |
| `proxy.connect-timeout-ms` | `1000` | 新建上游连接超时，毫秒 |
| `proxy.request-timeout-ms` | `3000` | 每次业务尝试等待上游响应头的超时，毫秒 |
| `proxy.max-request-body-bytes` | `10485760` | 请求体上限，默认 10 MiB |
| `retry.max-attempts` | `2` | 最大尝试次数，包含首次发送 |
| `retry.status-codes` | `502,503,504` | 允许触发重试的上游错误状态码 |
| `management.path-prefix` | `/_proxy` | 保留管理路径前缀 |

启动时还会校验：线程数、权重、超时、周期、阈值和尝试次数均为正数；后端 ID 不含逗号或空白；后端 URI 必须含 host，且不能含用户凭据、query 或 fragment。请求体上限不能超过 Java 数组限制 `2147483639` 字节，实际可承受值还取决于堆内存与并发量。

健康路径和管理前缀必须是以 `/` 开头的 URI 路径，不能包含 authority、query 或 fragment；管理前缀还不能是 `/` 或以 `/` 结尾。端口 0 仅允许测试通过编程方式绑定临时端口，正式配置不接受。

`retry.status-codes` 可以显式填写 400–599 的错误码；未配置的 4xx/5xx 直接返回。例如显式加入 429 后，429 才会触发幂等请求重试。将该项设为空只关闭“按状态码重试”，连接/I/O/超时重试仍可能发生；要关闭全部业务重试，设置 `retry.max-attempts=1`。

## 6. 转发与容错语义

### URI、请求头和响应体

后端 URL 的路径作为前缀保留，不使用会丢弃基准路径的 `URI.resolve`。例如：

```text
后端基准 URI：http://127.0.0.1:9001/api/
客户端请求： /files/a%2Fb?q=a%2Bb
实际目标：   http://127.0.0.1:9001/api/files/a%2Fb?q=a%2Bb
健康目标：   http://127.0.0.1:9001/api/health
```

仅处理拼接处的斜杠，不解码再编码原始路径/查询，也不擅自规范化点路径或内部双斜杠。

请求和响应均过滤静态逐跳头及 `Connection` 动态声明的字段。请求的 Host、Content-Length 和 Transfer-Encoding 不直接复制，由 HTTP 客户端生成；代理追加 `X-Forwarded-For`，重建 `X-Forwarded-Host`、`X-Forwarded-Proto` 和 `X-Request-Id`，并添加 Via。

上游响应的状态码与安全响应头会保留，重定向不自动跟随。普通响应采用分块流式输出，不复制上游 Content-Length；HEAD、204、205、304 不向客户端发送正文。

### 健康检查与管理接口

后端按 `UNKNOWN / HEALTHY / UNHEALTHY` 管理，只有 HEALTHY 接收业务流量。检查使用 GET，200–399 视为成功，其他状态、连接失败和超时视为失败；收到响应头后关闭探测响应体，不等待完整正文。首次达到成功阈值后加入流量池，连续失败达到阈值后摘除，之后连续成功达到阈值再恢复。

`GET /_proxy/health` 在至少一个后端健康时返回 200，否则返回 503；“UP”不表示所有实例都健康，需同时检查 `healthyBackends`。该接口只提供 JSON，不是 Web 管理页面。

管理前缀及其子路径不转发到业务后端：未知管理路径返回 404，健康接口非 GET 请求返回 405。`/_proxy-other` 不属于管理路径。管理响应声明 `Connection: close`，避免未读取请求体时误复用连接。

### 超时、重试与最终结果

- 建连超时只影响新连接，不约束已复用连接。
- 每次转发独立设置请求超时，约束取得上游响应头的阶段。流式响应体没有独立的总体截止时间；多次尝试可能各耗用一次超时预算。
- 仅 `GET、HEAD、PUT、DELETE、OPTIONS` 自动重试；POST、PATCH 以及其他方法不自动重试。PUT/DELETE 的安全重放依赖后端正确实现跨实例幂等语义。
- 每个请求使用开始时的一份健康快照，不临时加入刚恢复的实例；每次选择排除已尝试的后端 ID。
- 有效尝试上限为 `min(retry.max-attempts, 初始快照中不同健康实例数)`；非重试方法最多一次。
- 建连失败、I/O/协议错误、超时或配置中的状态码可触发切换。重试前先关闭被放弃的响应体，不读取它的剩余正文，也不转发其响应头。
- 收到最终响应后开始向客户端提交；提交后的流错误只中断连接，不再重试。业务失败不直接修改健康状态，摘除与恢复仍由主动探测决定。

| 最终情况 | 客户端结果 |
| --- | --- |
| 请求开始时没有健康后端 | 503 |
| 最后一次未取得响应，原因为连接/I/O/协议错误 | 502 |
| 最后一次未取得响应，原因为超时 | 504 |
| 请求体超过上限 | 413，不发送上游请求 |
| 未预期内部异常 | 500 |
| 最后一次取得合法 HTTP 响应 | 保留该响应，包括重试次数耗尽后的 503 |
| 已提交响应后流中断 | 保留已提交状态并断开连接，不追加另一份响应 |

例如 A 返回 503、B 也返回 503，最终返回 B 的 503 及正文；A 超时、B 连接失败则返回 502，而不是沿用第一次的 504。

代理在 HttpClient 首次使用前设置 `jdk.httpclient.disableRetryConnect=true`、`jdk.httpclient.enableAllMethodRetry=false`、`jdk.httpclient.redirects.retrylimit=1`，避免 JDK 内部重放绕过业务尝试次数。作为库嵌入其他 JVM 时，宿主应在首次 HttpClient 使用前设置这些全局属性，不能依赖晚设置覆盖 JDK 已缓存的值。

## 7. 日志

启动信息输出到 stdout，访问日志和健康状态日志通过 JUL 输出到 stderr，均使用 UTF-8。日志留存与轮转由运行环境负责；重定向到文件时也需明确采用 UTF-8，不能假定旧版 PowerShell 的默认文件编码与程序输出一致。

每个业务或管理请求输出一条最终访问日志，而不是每次尝试一条：

```text
timestamp=2026-09-29T03:00:00Z requestId="示例请求ID" client="127.0.0.1" method="GET" path="/demo" backend="api-b" attempts=2 status=200 responseBytes=2 durationMs=1004 error="NONE"
```

字段包含时间、requestId、客户端地址、方法、原始 path、最终后端、实际尝试次数、最终状态、成功写出的正文字节数、总耗时毫秒和错误分类。未发生上游发送时，`backend="-"` 且 `attempts=0`。响应字节数不包含报文头、分块编码和被放弃的上游正文。

日志不记录 Authorization、Cookie、请求/响应正文、原始异常内容或可能携带令牌的查询串。总耗时从处理器开始执行计量，不包含进入工作线程前的排队时间；中断且未发出状态行时，日志中的状态可能为 0。

健康日志只在状态变化时输出，例如：

```text
timestamp=2026-09-29T03:00:00Z event=backend_health backend="api-a" from=UNKNOWN to=HEALTHY
timestamp=2026-09-29T03:00:10Z event=backend_health backend="api-a" from=HEALTHY to=UNHEALTHY
timestamp=2026-09-29T03:00:15Z event=backend_health backend="api-a" from=UNHEALTHY to=HEALTHY
```

## 8. 测试与验收

### 单元与集成测试

```powershell
# 从空 target 开始运行全部测试；该命令不生成 JAR
mvn clean test

# 编译、测试并打包；需要可执行 JAR 时使用此命令
mvn clean package

# 只运行超时、重试及 JDK 内部重试相关测试
mvn "-Dtest=RequestForwarderRetryTest,RetryPolicyTest,RetryIntegrationTest,JdkHttpTransportTest" test
```

测试覆盖配置默认值与非法边界、两种均衡算法、并发选择与状态更新、URI 与头过滤、常见 HTTP 方法、二进制正文、10 MiB 请求体限制、健康摘除恢复、超时和错误映射、请求重放次数、流中断、重复启停及资源关闭。

集成测试使用回环地址和动态端口。大部分故障由可控假传输层注入；少量真实慢后端与断连接测试验证实际网络行为。测试会自行创建并关闭服务器、线程池和子 JVM，不依赖手动启动的 9001/9002 演示实例。

### 独立 JAR 双后端验收

先执行 `mvn clean package`，再运行：

```powershell
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar round-robin
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar weighted-round-robin
```

该程序会以独立进程启动实际 JAR，自动创建两台本地后端及临时配置，执行以下检查，成功时输出 `JAR_SMOKE_OK`：

- 连续 400 次无故障请求：普通轮询 A/B 各 200 次，加权 3:1 时 A/B 分别为 300/100 次，同时验证每次选择的确定性序列。
- 停止 A 后只访问 B，重启 A 后重新参与均衡；全部后端停机并被摘除后返回 503。
- A 保持健康接口正常但业务超时：GET 在 A 尝试一次后切换到 B 成功，POST 仅尝试 A 一次并返回 504。
- 声明请求体长度为 10 MiB + 1 时立即返回 413，不等待上传，也不向后端发送请求；实际流式读取超限由 JUnit 测试另行覆盖。
- 保留 `/api` 前缀、`a%2Fb` 路径、`q=a%2Bb` 查询及中文正文；中文文件名、后端 ID 和日志可正确读取。
- 每个特殊请求只有一条最终访问事件，状态、尝试次数与最终后端正确，日志不泄漏查询串、Authorization、Cookie 或正文。

临时配置、`stdout.log`、`access.log` 和 `result.txt` 位于命令输出的 `target/test-data/jar-smoke-*` 目录，执行 `mvn clean` 后会被清理。需要留存时请先复制到项目外的验收归档目录。Windows 的 Java classpath 分隔符为分号，不能在上述命令中替换为冒号。

最新完整构建、测试数量、两种策略的实际结果和十项场景对照，统一记录在 [阶段 6 系统验收文档](docs/stage-6.md)。独立 JAR 检查不包含在 JUnit 测试计数中；它使用临时的 750ms 业务超时、100ms 检查周期和连续 2 次成功/失败阈值以缩短验收时间，不修改正式配置的默认值。

## 9. 代码与设计文档导航

主代码位于 `src/main/java/com/example/proxy`，测试位于 `src/test/java/com/example/proxy`。核心模块如下：

- `ProxyApplication`：CLI、UTF-8 配置加载、组件装配及关闭钩子。
- `config`：不可变配置模型及启动校验。
- `backend`、`balance`：后端状态注册表、健康快照、轮询与平滑加权轮询。
- `proxy`、`transport`：请求体缓冲、URI/报文头处理、重试、HTTP 调用和响应写出。
- `health`、`lifecycle`：独立健康调度、状态转换与资源关闭。
- `logging`：单条最终访问事件和健康状态变化日志。

完整设计、验收记录与历史阶段说明：

- [架构与设计：模块、协议、状态机、并发及生命周期](docs/design.md)
- [阶段 6：系统验收与交付记录](docs/stage-6.md)
- [阶段 2：最小 HTTP 转发链路](docs/stage-2.md)
- [阶段 3：两种负载均衡算法](docs/stage-3.md)
- [阶段 4：健康检查与生命周期](docs/stage-4.md)
- [阶段 5：超时、有限重试与日志](docs/stage-5.md)

阶段 2–5 文档保留了历史交付边界；当前默认行为以本文件及架构设计文档为准，不应将早期的“只转发一次”或“静态后端池”视作当前生产启动行为。

## 10. 已知限制与常见问题

- 不包含限流、Web 管理界面、Docker 部署、HTTPS 终止、WebSocket、CONNECT 隧道、服务发现和配置热加载；协议升级请求不转发。
- 固定工作线程池负责阻塞式请求处理，等待队列容量为工作线程数的 2 倍；过载时可能关闭新连接，不能保证返回友好的 503。慢上游与慢客户端会占用工作线程，管理接口也共享该工作池。
- 健康检查使用独立调度器，最多 4 个线程，不与业务工作线程共用；探测慢或后端较多时，实际检查时间可能延后。
- 请求体全量缓冲到内存，默认每个请求最多 10 MiB。提高上限或并发量会提高堆内存需求；没有大文件流式上传和磁盘溢写支持。
- 不保证端到端总耗时、响应正文读取截止时间或“请求恰好执行一次”；流式响应中断后不重试，也不尝试在已提交响应后改写状态码。
- 管理接口没有单独认证或管理端口，默认只绑定回环地址。若改为 `0.0.0.0` 等可外部访问的地址，需自行提供网络访问控制。客户端传入的 X-Forwarded-For 前缀也不能直接当作可信身份。
- HTTPS 后端使用 JDK 的证书信任与校验机制，不提供跳过证书校验的配置；该能力不等于代理监听端支持 HTTPS。
- 不追求完整 HTTP 协议兼容性、生产级吞吐量或高可用部署，演示后端也不具备生产安全加固。

常见排查方式：

- **启动提示配置不存在**：确认当前工作目录，或通过 `--config` 指定实际文件路径；包含空格的路径加引号。
- **启动提示端口占用**：停止占用该端口的已知进程，或修改配置中的监听端口，不要同时启动两种代理配置占用同一端口。
- **持续返回 503**：先看健康数量和状态转换日志，确认后端已启动、health-path 与基准路径组合正确，且健康接口返回 200–399。
- **返回 502/504**：查看最终后端、attempts 和 error 字段；502 通常对应连接/I/O/协议错误，504 对应获得上游响应头之前的超时。也可能是上游原样返回的状态，可通过 `UPSTREAM_HTTP_ERROR` 区分。
- **修改配置后未生效**：代理不支持热加载，需重启。
- **中文乱码**：确认文件保存为 UTF-8、终端输出编码正确；读取日志时使用 `Get-Content -Encoding UTF8`，不要仅修改文件扩展名来转换编码。
