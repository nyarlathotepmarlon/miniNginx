# 阶段 4：健康检查与生命周期

本文保留阶段 4 的交付说明。当前版本已进入[阶段 5：超时与有限重试](stage-5.md)，本页的健康状态机和生命周期仍然适用；以下“尚不重试”仅指阶段 4 的历史行为。

本阶段接入主动健康检查：正常 CLI 启动使用 `BackendRegistry`，所有后端初始为 `UNKNOWN`；探测满足阈值后加入流量池，故障后自动摘除，恢复后自动加入。阶段 3 的两种均衡器无需修改算法即可使用真实健康快照。

仍不实现阶段 5 的业务重试。每个业务请求最多发送一次上游请求；连接/请求超时和 JDK 内部重试禁用规则沿用阶段 2。阶段 2、3 文档现在是历史阶段说明。

## 模块职责

- `BackendProbe`：一次可中断的探测接口，可注入确定性假实现。
- `HttpBackendProbe`：对“后端基准 URI + health-path”执行 GET，保留基准路径和百分号编码，用 `health.timeout-ms` 设置请求超时。
- `HealthChecker`：创建独立调度器，为每个实例安排周期任务，记录结果并触发状态更新。保留包级 `checkOnce()`，供未启动调度器的单元测试直接调用。
- `BackendRegistry`：沿用阶段 1 的线程安全状态机、连续计数器和不可变健康快照。
- `HealthLogger`：仅记录真实状态转换，使用 JUL、UTF-8 和控制字符转义，不输出每轮成功/失败日志或异常原文。
- `ReverseProxyServer`：组装健康注册表、探测器、均衡器、转发器，负责监听、调度器、活动响应和工作线程池的生命周期。
- `ExecutorShutdown`：统一执行短暂宽限、取消剩余任务、等待终止并保留调用线程中断标记。

正常 `ReverseProxyServer.create(config)` 启用主动检查。`createWithHealthChecks(...)` 允许替换探测器、传输层和日志接收器；原来的外部 `BackendPool` 注入入口不创建调度器，供静态转发/算法测试使用，管理接口会标记 `healthChecksEnabled=false`。

## 探测规则与状态机

配置示例（已有默认配置和加权配置均可使用）：

```properties
backend.api-a.url=http://127.0.0.1:9001/api
backend.api-a.health-path=/health
health.interval-ms=5000
health.timeout-ms=1000
health.failure-threshold=2
health.success-threshold=1
```

探测目标为 `http://127.0.0.1:9001/api/health`，不是根路径 `/health`。

- 状态码 `200–399` 视为成功；不跟随重定向，因此 302 自身即为成功，不访问 Location。
- 其他状态码、连接失败、超时、I/O 异常以及探测器的运行时异常视为失败。
- 收到响应头后即判定状态并关闭响应体，不读取、缓存或等待响应正文结束，避免慢响应体占用探测线程。
- 连接超时仍由 `proxy.connect-timeout-ms` 限制新建连接；健康请求另用 `health.timeout-ms` 限制等待上游响应阶段，与业务请求的 `proxy.request-timeout-ms` 分开。

| 当前状态 | 探测结果 | 行为 |
| --- | --- | --- |
| UNKNOWN / UNHEALTHY | 成功 | 清零失败计数，累计连续成功；达到成功阈值后变为 HEALTHY |
| HEALTHY | 成功 | 保持 HEALTHY，清零失败计数 |
| UNKNOWN / HEALTHY | 失败 | 清零成功计数，累计连续失败；达到失败阈值后变为 UNHEALTHY |
| UNHEALTHY | 失败 | 保持 UNHEALTHY，清零成功计数 |

只有 HEALTHY 参与均衡。默认成功阈值为 1，首次成功即可进入；配置为 2 时必须连续成功两次。故障判定存在探测间隔与阈值带来的延迟；达到失败阈值前仍可能有请求发往故障实例。

业务请求继续使用开始时的健康快照。已经选择或正在转发的请求不会因后续健康状态改变而被重新选择或取消；本阶段没有失败重试。

## 调度、异常隔离与日志

使用独立 `ScheduledThreadPoolExecutor`，线程数为 `min(max(1, 后端数量), 4)`。启动后各实例初始延迟均为 0，随后用 `scheduleAtFixedRate` 按配置周期检查。

- 同一实例的周期任务不重叠；慢探测可能延后下次运行。固定周期任务超期后会尽快补上，线程总量不会超过上限。
- 超过 4 个实例时，部分探测需要等待空闲调度线程，不能承诺所有实例在同一时刻完成检查。
- 单个探测的 I/O 或运行时异常被转换为失败，不会取消后续周期；日志接收器的运行时异常也不会终止调度。JVM 致命错误不被吞掉。
- 健康线程与固定业务工作线程池独立；业务线程被慢请求占满时，探测仍能推进。但管理 HTTP 请求仍使用业务工作池，过载时不能保证管理接口及时响应。

健康日志示例：

```text
timestamp=... event=backend_health backend="api-a" from=UNKNOWN to=HEALTHY
timestamp=... event=backend_health backend="api-a" from=HEALTHY to=UNHEALTHY
timestamp=... event=backend_health backend="api-a" from=UNHEALTHY to=HEALTHY
```

未达到阈值、状态不变的后续探测不打印状态日志。探测流量不会进入代理业务访问日志；下游业务和管理请求继续每请求一条最终访问日志。

## 管理接口

`GET /_proxy/health` 在至少一个后端健康时返回 200，否则返回 503：

```json
{"status":"UP","mode":"active-health-checks","healthChecksEnabled":true,"loadBalancingStrategy":"round-robin","totalBackends":2,"healthyBackends":2,"eligibleBackends":2}
```

`healthyBackends` 为真实注册表健康快照数量，`eligibleBackends` 保留兼容性。在首次探测完成且达到成功阈值前，两者均为 0，业务请求和管理健康请求返回 503。路径边界、未知管理路径 404 和非 GET 健康请求 405 沿用之前规则。

## 启动与关闭

`create()` 仅组装组件，不绑定监听端口、不启动探测任务。`start()` 才创建并绑定 HttpServer，启动监听后启动健康调度器；重复 start 无副作用，关闭后不能再次 start。端口占用等启动错误会清理已组装组件并由 CLI 以非零退出码报告。

在 `start()` 前调用 `address()` 返回配置地址；若配置端口为 0，此时仍为 0。启动成功后返回实际绑定端口。延迟创建也避开了本机 JDK 17 的边界行为：尚未运行的 HttpServer 调度线程不能完成 selector 的关闭，提前绑定后直接 stop 可能在 Windows 上保留待注销端口。

关闭顺序：

1. 停止接受新连接，给正在处理的 HttpExchange 最多 1 秒宽限期。
2. 将健康检查器标为关闭，禁止新的状态更新；停止周期调度，最多等 1 秒后中断未完成的探测，再等待最多 3 秒终止。
3. 关闭活动上下游响应流；停止工作池，最多等 1 秒，必要时中断剩余任务并再等最多 3 秒。
4. 释放终止等待器；重复 close 不重复启动或调度。并发 start/close 被序列化。

健康检查器被标记关闭后的迟到探测结果、由关闭造成的中断不会被记录为后端恢复或故障。健康线程和业务工作线程为代理拥有的非守护线程，测试验证关闭后终止。使用者注入的探测器必须响应中断，日志接收器不能无限阻塞；不配合取消的扩展会得到明确终止失败异常，而不是使用不安全的强杀线程 API。Java 17 HttpClient 的内部守护线程和连接池仍由 JDK 管理。

## Windows 本地验收

先执行 `mvn clean package`，分别打开两个终端运行：

```powershell
java .\examples\EchoBackend.java 9001
java .\examples\EchoBackend.java 9002
```

第三个终端运行普通轮询代理（加权模式替换为 `proxy-weighted.properties`）：

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\proxy.properties
```

第四个终端查看状态与流量：

```powershell
curl.exe -i "http://127.0.0.1:8080/_proxy/health"
1..8 | ForEach-Object { curl.exe --silent "http://127.0.0.1:8080/demo" }
```

停止 9001 后端，等待达到连续失败阈值，再请求应全部进入 9002；重启 9001，达到连续成功阈值后会重新参与均衡。停止两个后端并等待检查后，业务请求及管理健康接口均返回 503。默认周期 5 秒、失败阈值 2，摘除不是瞬时发生；首次检查立即执行。

独立 JAR 的自动化双后端故障恢复验收使用短周期和临时端口：

```powershell
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar round-robin
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar weighted-round-robin
```

每次运行自动检查：正常分配 8 次、停止 A 后仅 B 接收 4 次、A 同端口重启后恢复分配 4 次、全部后端停止后返回 503，并验证 UTF-8 中文配置、访问/健康日志及真实 POST 正文。进程和后端均在结束后清理。

单元与集成测试另外覆盖：成功/失败阈值、相反计数清零、只记录转换、响应状态边界、GET/原始 URI、探测超时、不等慢响应体、探测及日志异常后继续调度、最多 4 个探测线程、同实例不重叠、工作池占满时继续探测、首次探测阻塞时返回 503、启动失败清理、关闭时取消真实探测、迟到结果丢弃、端口释放、并发/重复启停和调用线程中断状态保留。
