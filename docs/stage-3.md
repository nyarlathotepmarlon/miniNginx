# 阶段 3：负载均衡

本文保留阶段 3 的交付说明。当前默认启动入口已进入[阶段 5：超时与有限重试](stage-5.md)，启用主动健康检查及幂等请求重试；本页的两种算法设计仍然适用。

本阶段将阶段 2 的单后端代理扩展为可运行的多后端代理，实现普通轮询与平滑加权轮询。HTTP 转发、超时边界、流中断处理、请求体限制、访问日志及 JDK 内部重试设置沿用阶段 2。

## 交付边界

- 默认启动入口使用所有 `backends`，不再只使用第一个实例。`load-balancer.strategy` 现在实际决定请求分配方式。
- 暂无健康调度器：运行时通过 `StaticBackendPool` 将配置后端显式作为可选候选。它不是健康探测结果，也不改变 `BackendRegistry` 初始为 `UNKNOWN` 的规则。
- 测试可以注入 `BackendRegistry` 并手动记录成功/失败，验证 `UNKNOWN → HEALTHY → UNHEALTHY → HEALTHY` 对真实 HTTP 请求分配的影响。没有提前实现阶段 4 的周期探测。
- 每个业务请求仍最多执行一次上游发送。后端失败不自动摘除、不切换重试；`retry.max-attempts` 和 `retry.status-codes` 尚不控制业务重试。相关功能分别留在阶段 4、5。

## 模块与选择语义

`ReverseProxyServer` 根据配置通过 `LoadBalancer.create(strategy)` 创建该服务器独享的均衡器，将候选池、均衡器、传输层与日志组装到 `RequestForwarder`。支持注入候选池和假传输层，也保留直接注入均衡器的测试入口。

`LoadBalancer.select(candidates, excludedBackendIds)`：

1. 输入当前请求取得的不可变快照与已尝试后端 ID 集合。
2. 只选择 `HEALTHY` 且未被排除的实例；没有可选实例时返回空结果。
3. 不修改输入，返回当前调用快照中的实例，不返回缓存的过期健康对象。
4. 管理请求不调用均衡器，不消耗轮询位置；业务请求选择一次，整个请求继续使用该快照。

排除集合为阶段 5 预留，本阶段生产请求传入空集合。一个请求不断把返回的 ID 加入排除集合后，最多能选择快照内不同健康实例数次；均衡器本身不执行请求，也不决定最大重试次数。

## 普通轮询

`RoundRobinLoadBalancer` 对当前健康、未排除列表使用原子递增计数器，并用 `Math.floorMod(ticket, size)` 取得非负下标。空候选不递增计数器。

- 稳定列表 `[A, B]` 的选择顺序为 `A, B, A, B`。
- 忽略后端权重；配置 `3:1` 也仍然平均分配。
- 候选变化后把当前计数器应用于新列表，不缓存已摘除实例，不承诺变更后从第一个实例开始。
- 计数器整数回绕后下标仍合法；在非二次幂数量的列表上，回绕点可能发生一次调度相位跳变。

## 平滑加权轮询

`SmoothWeightedRoundRobinLoadBalancer` 用同步锁保护完整的状态比较和选择过程。每个可选节点有正整数配置权重 `weight` 和 `long` 累计权重 `currentWeight`。单次选择：

1. 给每个未排除的健康节点增加自己的配置权重。
2. 选择累计权重最大的节点。
3. 从选中节点的累计权重中减去本次参与选择节点的权重总和。

累计权重相等时选择当前调度周期中顺序在前的节点；周期首次建立时采用配置快照顺序。仅重排同一集合的输入列表不会改变已有周期的平局顺序。

例如 A=3、B=1，从全零状态开始：

| 请求 | 加权后的 `(A, B)` | 选中 | 扣减后的 `(A, B)` |
| --- | --- | --- | --- |
| 1 | `(3, 1)` | A | `(-1, 1)` |
| 2 | `(2, 2)` | A | `(-2, 2)` |
| 3 | `(1, 3)` | B | `(1, -1)` |
| 4 | `(4, 0)` | A | `(0, 0)` |

因此确定性序列为 `A, A, B, A`，稳定候选集合且没有请求级排除时，长期请求数按 3:1 分配。权重和累计值采用 `long` 运算，避免多个合法 `int` 权重相加溢出。

### 何时重建累计状态

按后端 **ID、基准 URI、配置权重和健康集合** 的值比较，而不是比较列表或快照对象引用。新增、摘除、恢复、替换地址或权重变化时，重建整个当前健康集合的零累计状态。健康集合变为空也会清空状态。

以下变化不会重置：新创建的等价快照、成功/失败计数变化、健康路径变化、同一集合的列表顺序变化，以及当前请求的排除集合变化。排除节点本次既不累计权重，也不参与权重总和；全部被排除时不推进累计权重。

重建后重新开始一个分配周期；不保证短暂上下线期间仍达到全局精确权重比例。

## 并发与管理接口

普通轮询使用原子计数器；加权轮询使用实例级锁，每个代理有独立状态。两种算法时间复杂度均为 O(N)，不在锁内进行网络 I/O。并发请求的选择有确定的序列或分布，但响应完成顺序取决于上游和客户端速度，不能用完成顺序推断选择顺序。

`GET /_proxy/health` 示例：

```json
{"status":"UP","mode":"static-backends","healthChecksEnabled":false,"loadBalancingStrategy":"round-robin","totalBackends":2,"eligibleBackends":2}
```

至少存在一个当前健康/静态候选时返回 200，否则返回 503。`healthChecksEnabled=false` 明确说明这不是主动健康探测结果：即使配置中的服务器没有启动，默认静态池仍报告存在候选。阶段 4 会接入真实健康语义。

## Windows 本地双后端演示

在项目根目录执行 `mvn clean package`，在两个终端分别启动后端：

```powershell
# 终端 A
java .\examples\EchoBackend.java 9001
# 终端 B
java .\examples\EchoBackend.java 9002
```

第三个终端启动默认普通轮询代理：

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\proxy.properties
```

第四个终端顺序请求，观察正文里的 `backend=9001`、`backend=9002` 交替出现：

```powershell
1..8 | ForEach-Object { curl.exe --silent "http://127.0.0.1:8080/demo" }
curl.exe -i "http://127.0.0.1:8080/_proxy/health"
```

按 Ctrl+C 停止代理，再使用加权示例配置重启（不能同时占用 8080）：

```powershell
java -jar .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar --config .\config\proxy-weighted.properties
```

重复请求，可观察到 `9001, 9001, 9002, 9001` 的周期。更改配置需重启，不支持热加载。此时停止任一后端会使选中它的请求失败；自动摘除和恢复须等待阶段 4。

## 自动化验收

```powershell
mvn clean test
mvn package
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar round-robin
java -cp "target/test-classes;target/classes" com.example.proxy.support.JarSmokeCheck .\target\jdk-reverse-proxy-0.1.0-SNAPSHOT.jar weighted-round-robin
```

也可用 `mvn clean package` 一次完成干净编译、测试和打包。

- 算法测试：确定性序列、长期分布、空列表/单实例、非健康实例过滤、排除已尝试实例、整数回绕、超大合法权重总和、快照刷新、候选增减/恢复、地址及权重变更。
- 并发测试：8 个线程共 4000 次稳定候选选择，核对精确分布；并行注册表状态更新与候选选择，验证输出属于该请求取得的健康快照。
- 真实 HTTP 测试：回环地址端口 0 双后端，两种策略各 80 次业务请求，核对序列、数量、URI 和访问日志；注入注册表验证状态切换；503 响应不触发隐式故障切换。
- 独立 JAR 验收：启动两个本机回显后端和一个独立代理进程，每种策略发送 8 次真实 POST，验证顺序、编码 URI、中文配置与正文、管理接口及每请求一条日志；执行后清理进程。

所有网络测试均不依赖互联网或固定服务端口，资源关闭、请求保护与 HTTP 协议边界继续由阶段 2 回归测试覆盖。
