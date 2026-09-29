# 阶段 2：最小可运行反向代理

本文保留阶段 2 的交付说明。当前版本已进入[阶段 5：超时与有限重试](stage-5.md)，默认启用多后端均衡、主动探测及幂等请求重试，当前语义与验收方式请以阶段 5 文档为准。

阶段 2 已实现真实单后端转发。默认使用 `backends` 列表中第一个实例；启动信息明确输出选中的 ID 和 URL。其余实例、配置的均衡策略、主动健康探测和业务重试暂不启用，留到阶段 3～5。`retry.max-attempts` 此时不改变每请求一次的尝试次数。

## Windows 本机运行

要求 JDK 17 和 Maven。在项目根目录运行，所有文件和控制台日志按 UTF-8 编码。

```powershell
mvn clean package
```

打开第一个终端，启动仅用于本地演示的回显后端（JDK 支持直接运行单个 Java 源文件）：

```powershell
java .\examples\EchoBackend.java 9001
```

第二个终端启动代理。默认配置位于 `config/proxy.properties`：

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar
# 或显式指定 UTF-8 配置文件
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\proxy.properties
```

第三个终端发送请求（使用 `curl.exe` 避免 PowerShell 的同名别名）：

```powershell
curl.exe -i "http://127.0.0.1:8080/files/a%2Fb?q=a%2Bb"
curl.exe -i -X POST -H "Content-Type: text/plain" --data-binary "hello proxy" "http://127.0.0.1:8080/echo"
curl.exe -i "http://127.0.0.1:8080/_proxy/health"
```

前两个请求会到达 9001，返回回显内容及 `X-Demo-Backend`。按 Ctrl+C 关闭各进程。端口被占用或配置非法时，代理打印错误并以退出码 1 结束。

## 转发规则

- `HttpServer → RequestForwarder → BackendPool/LoadBalancer → JdkHttpTransport → ResponseWriter`。阶段 2 使用单实例静态池及 `SingleBackendSelector`；后续替换选择器和候选池即可。
- 保留 HTTP 方法、原始路径、原始查询串、二进制请求体和普通端到端请求头。`/api/` 与 `/files/a%2Fb?q=a%2Bb` 拼接为 `/api/files/a%2Fb?q=a%2Bb`。仅合并连接处的一个斜杠，不解码 `%`，不规范化双斜杠和点路径。
- 入站仅接受 origin-form 请求目标（`/path?query`）；不提供正向代理、CONNECT 隧道、WebSocket、Upgrade 或 HTTP/2 入站功能。上游固定 HTTP/1.1，允许 HTTP/HTTPS，TLS 使用 JDK 默认信任校验。
- 请求体有界内存缓冲，默认上限 10 MiB。已声明超限以及未知长度/分块上传实际超限均返回 413，后端调用次数为 0。配置上限受 Java 字节数组大小约束；缓冲和复制存在内存开销，不适合超大上传。
- 请求、响应两个方向都过滤逐跳头，并解析 `Connection` 列出的所有扩展头。`Host` 和请求长度由 JDK 重建，`Expect` 不向上游复制。生成请求 ID 并写入上游请求、下游响应和日志；追加 `X-Forwarded-For`，重建 `X-Forwarded-Host/Proto`，添加 `Via`。转发链元数据不应被后端当作已认证客户端身份。
- 保留响应状态码、多值响应头（例如多个 `Set-Cookie`）和响应体。删除上游 `Content-Length` 和 `Transfer-Encoding`，由 `HttpServer` 重新进行分块传输。HEAD、204、205、304 不写响应体。302 等重定向直接返回客户端，不由代理追踪。
- 合法上游 4xx/5xx 原样返回，包括状态码、响应头和响应体。无可用候选实例返回 503；接收响应头前上游 I/O 失败返回 502，超时返回 504。本阶段对所有方法都只有一次业务尝试。
- 响应头一经提交，响应体中断只中止连接、关闭上下游流并记录失败，不能重试或改写状态码。输出流的中止包装利用 JDK HttpServer 在关闭输出流失败时关闭底层连接的行为，避免给截断响应补发正常的分块结束标志；原始 socket 测试覆盖了这一点。

## 超时和 JDK 运行参数

`proxy.connect-timeout-ms` 只限制新建连接；`proxy.request-timeout-ms` 通过 `HttpRequest.timeout()` 应用于每次上游发送。响应使用 `BodyHandlers.ofInputStream()`，响应头到达后可以开始转发，**不承诺完整响应体的端到端截止时间**。也没有客户端上传/下载的独立空闲超时。

应用在首次网络操作前固定以下 JVM 全局属性；测试进程也在启动时设置它们，避免其他测试提前初始化 JDK 的缓存值：

```text
jdk.httpclient.disableRetryConnect=true
jdk.httpclient.enableAllMethodRetry=false
jdk.httpclient.redirects.retrylimit=1
sun.net.httpserver.drainAmount=0
```

本机 JDK 17 源码确认 `disableRetryConnect` 不覆盖连接失效后的全部重试路径，因此额外把内部发送上限固定为 1。独立 JVM 故障测试在服务端读完 GET/POST/PATCH 请求后直接断开，并确认实际 HTTP 请求各只有一次。一次尝试定义为一次 `UpstreamTransport.send` 调用，不是 TCP 数据包或 DNS 查询次数。

`drainAmount=0` 避免拒绝超大请求后，JDK 在关闭请求流时继续等待客户端上传剩余内容。未读完请求体的连接直接结束；完整正常请求仍可复用连接。这些设置面向独立代理进程；若嵌入其他已经使用 HttpClient/HttpServer 的应用，必须在 JVM 启动时设置，不能依赖运行中修改生效。

参考：[Java 17 网络属性](https://docs.oracle.com/en/java/javase/17/core/java-networking.html)、[HttpExchange 响应长度语义](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html#sendResponseHeaders(int,long))、[流式响应体与资源关闭](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscribers.html#ofInputStream())。

## 管理接口、日志和容量

`GET /_proxy/health` 报告当前运行模式及候选实例数：

```json
{"status":"UP","mode":"single-backend","healthChecksEnabled":false,"totalBackends":2,"eligibleBackends":1}
```

这里的 UP 仅表示存在静态候选实例，**不代表后端已经健康探测成功**。真实健康语义在阶段 4 接入。管理域内未知路径为 404，健康接口非 GET 方法为 405。按原始路径和路径段边界匹配，因此 `/_proxy-other`、`/_proxy%2Fhealth` 仍为业务路径。

每个进入处理器的请求只生成一行 UTF-8 访问日志，包括请求 ID、客户端、方法、原始路径、选中后端、尝试次数、已提交状态码、成功写出的正文字节数、总耗时和错误分类。为避免敏感信息泄露，不记录 query、请求/响应头、请求体或异常原文。控制字符被转义。响应提交前客户端中断时，状态可能为 0。

固定 `N=proxy.worker-threads` 个工作线程，等待队列容量 `2N`。队列满时拒绝任务，JDK 关闭该入站连接；此时无法承诺返回 HTTP 503，也不会产生处理器访问日志。不会让 dispatcher 线程执行阻塞转发。上游 HttpClient 使用独立的 JDK 执行器，避免共享饱和工作池造成死锁。N 只限制业务工作线程，不是整个 JVM 的线程或 TCP 连接总数。慢上传、慢上游响应体和慢客户端下载依然可能占满工作线程。

`close()` 可重复调用，关闭监听、活动响应流并中断工作线程，等待至多 5 秒。当前关闭方式是立即关闭；更完整的生命周期管理在阶段 4 增量实现。Java 17 的 HttpClient 没有公开 close API，其内部连接池和守护线程由 JDK 管理，进程退出时释放。

## 验收

```powershell
mvn clean package
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar
```

单元/本机集成测试覆盖编码 URI、双向头过滤、方法和二进制正文、HEAD/204/304、空响应、重定向、已知及未知长度 413、管理边界、超时/502/最终 503、响应中断资源关闭、逐块转发、日志脱敏和独立进程内部重试验证。JAR 冒烟检查启动本机临时后端和独立代理进程，执行真实 POST 与管理请求，并清理进程。所有自动化网络测试使用回环地址。
