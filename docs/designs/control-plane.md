# Agent Control Plane — 设计文档

> 结构采用 gstack `/plan-eng-review` 视角（Context / 关键定义 / 架构与数据流 / 设计决策 / 边界 / 性能(DFX) / 契约 / 测试 / 验证）。
> 设计决策逐条链接到 [`docs/decisions/`](../decisions/) 下的 ADR HTML。

## 1. Context

CampusClaw 需要一个「控制面」来管理数据面节点（data-plane nodes）的注册、心跳、能力聚合与运行时调度。控制面提供 REST API 供数据面节点注册自身、上报指标、接收调度决策。

最初设计为独立 sidecar 进程（`modules/agent-control-plane` 独立模块 + `AgentControlPlaneApplication` 主类 + `spring-boot-starter-web` servlet 栈）。在 code review 中发现该设计与主进程存在两个硬冲突（详见 [ADR-0007](../decisions/0007-control-plane-merge-into-agent-core.html)），遂改为**合入 `agent-core` 模块、复用 `CampusClawApplication` 主进程、共享 webflux 服务器**。

控制面 v1 覆盖三段增量（MR-A / MR-B / MR-C）：

| MR | 内容 |
|---|---|
| MR-A | domain 模型 + `ControlPlaneProperties` + `ControlPlaneConfiguration`（Clock bean + `@EnableScheduling`） |
| MR-B | `NodeRegistry` + `HealthCheckScheduler` + `NodeRoutes`（webflux RouterFunction）+ `ControlPlaneExceptionHandler` |
| MR-C | `RuntimeScheduler` + `ScheduleRequest` / `ScheduleDecision` + `RuntimeRoutes`（webflux RouterFunction） |

## 2. 关键定义

| 类型 | 模块 | 说明 |
|---|---|---|
| `NodeInfo` | agent-core / `agent.controlplane.domain` | 不可变快照：nodeId、host、port、version、capabilities、status、registeredAt、lastHeartbeatAt、metrics。compact constructor 校验 non-blank id / port 范围 / non-null 必填；`withHeartbeat` / `withStatus` 返回新拷贝 |
| `NodeStatus` | agent-core / `agent.controlplane.domain` | 枚举：`ACTIVE` / `STALE` / `DEREGISTERED` |
| `NodeMetrics` | agent-core / `agent.controlplane.domain` | 心跳上报的容量指标：activeAgents、queuedTasks、cpuLoad（拒绝 NaN / ±Infinity）、memoryUsedMb |
| `RuntimeCapability` | agent-core / `agent.controlplane.domain` | 枚举：模型能力、`TOOL_BASH`、`TOOL_FILE_IO` 和子 Agent 能力；Docker Sandbox 能力已删除 |
| `ScheduleRequest` | agent-core / `agent.controlplane.domain` | 调度入参 record：requiredCapabilities（null 标准化为空集）+ preferredNodeId（可选亲和） |
| `ScheduleDecision` | agent-core / `agent.controlplane.domain` | 调度出参 record：nodeId、host、port、reason（"affinity" / "round-robin" / "least-active"） |
| `ControlPlaneProperties` | agent-core / `agent.controlplane.config` | `@ConfigurationProperties("controlplane")` record，绑定 `controlplane.heartbeat.{ttl,sweep-interval,grace-after-stale}` |
| `NodeRegistry` | agent-core / `agent.controlplane.service` | `ConcurrentHashMap` 内存注册表；register / heartbeat / deregister / findNode / listAll / sweep |
| `HealthCheckScheduler` | agent-core / `agent.controlplane.service` | `@Scheduled` fixedDelayString 周期调用 `registry.sweep()` |
| `RuntimeScheduler` | agent-core / `agent.controlplane.service` | 调度策略：preferredNodeId 命中 → affinity；否则 ACTIVE∩capabilities 间 round-robin |
| `NodeRoutes` | coding-agent-cli / `codingagent.controlplane.api` | webflux `RouterFunction` Bean，5 个节点生命周期端点 |
| `RuntimeRoutes` | coding-agent-cli / `codingagent.controlplane.api` | webflux `RouterFunction` Bean，3 个运行时聚合端点 |
| `ControlPlaneExceptionHandler` | coding-agent-cli / `codingagent.controlplane.error` | `HandlerFilterFunction`，NoSuchElementException → 404、IllegalArgumentException → 400、ServerWebInputException 解包根因 → 400 |

## 3. 架构与数据流

```
                        ┌─────────────────────────────────────────┐
                        │         CampusClawApplication            │
                        │  (@SpringBootApplication, scanBasePackages│
                        │   = "com.campusclaw")                    │
                        │                                         │
   ┌──────────────┐     │  ┌─────────────────────────────────┐    │
   │  data-plane  │────►│  │  agent-core                     │    │
   │  node        │     │  │  └─ agent/controlplane/         │    │
   │  (HTTP)      │◄────│  │     ├─ domain/  (6 records)     │    │
   └──────────────┘     │  │     ├─ config/  (props + config)│    │
                        │  │     └─ service/ (registry +     │    │
                        │  │                 scheduler)       │    │
                        │  │                                 │    │
                        │  │  coding-agent-cli               │    │
                        │  │  └─ codingagent/controlplane/   │    │
                        │  │     ├─ api/     (RouterFunction) │    │
                        │  │     └─ error/   (HandlerFilter)  │    │
                        │  └─────────────────────────────────┘    │
                        │                                         │
                        │  ServerMode (reactor-netty, --mode     │
                        │  server) 合并所有 RouterFunction bean   │
                        └─────────────────────────────────────────┘
```

**请求流（以 `POST /api/v1/nodes` 为例）**：

1. 数据面节点发 HTTP POST 到 `CampusClawApplication` 的 reactor-netty server
2. `ServerMode` 的 `buildRoutes` 把 `NodeRoutes.nodeControlPlaneRoutes` bean 通过 `.and()` 合入主路由链
3. `NodeRoutes` 的 handler 调 `registry.register(host, port, version, capabilities)`
4. `NodeRegistry` 生成 `node-<uuid>`、写 `ConcurrentHashMap`、返回 `NodeInfo`
5. handler 返回 `ServerResponse.created(URI).bodyValue(info)`（201 Created）
6. 如 `RegisterNodeRequest` 的 record compact constructor 抛 `IllegalArgumentException`（port=0），webflux 包成 `ServerWebInputException`，`ControlPlaneExceptionHandler` filter 解包根因返回 400

**心跳过期扫描流**：

1. `HealthCheckScheduler.sweep()` 每 `controlplane.heartbeat.sweep-interval`（默认 10s）触发
2. 调 `NodeRegistry.sweep()`：遍历所有节点，`lastHeartbeatAt + ttl < now` 且 `ACTIVE` → `STALE`；`lastHeartbeatAt + ttl + grace < now` 且 `STALE` → remove
3. CAS 失败（心跳并发赢了）返回 0，计数不虚高

## 4. 设计决策

| ID | 决策 | ADR |
|---|---|---|
| D1 | 控制面合入 `agent-core`（库模块）而非独立 sidecar 模块；`CampusClawApplication` 的 `scanBasePackages = "com.campusclaw"` 自动扫描 `agent.controlplane.*` 包 | [ADR-0007](../decisions/0007-control-plane-merge-into-agent-core.html) |
| D2 | HTTP 端点用 webflux `RouterFunction` 而非 `@RestController`，因为 `application.yml` 配 `web-application-type: none`，Spring Boot autoconf 不会注册 `@RestController` | [ADR-0008](../decisions/0008-control-plane-webflux-routerfunction.html) |
| D3 | `CampusClawCommand.runServerMode` 通过 `ApplicationContext.getBeansOfType(RouterFunction.class)` 收集所有声明式路由 bean，传给 `ServerMode.setExtraRoutes()` 合入主路由链 | [ADR-0009](../decisions/0009-collect-routerfunction-beans.html) |
| D4 | 控制面端点暂不鉴权；默认 localhost 绑定 + `--mode server` 受限场景下风险可控；鉴权作为显式延期决策记录 | [ADR-0010](../decisions/0010-defer-control-plane-auth.html) |

## 5. 边界情况

| # | 场景 | 行为 |
|---|---|---|
| E1 | 节点注册时 port=0 或 70000 | `RegisterNodeRequest` compact constructor 抛 `IllegalArgumentException` → `ControlPlaneExceptionHandler` 返回 400 |
| E2 | 心跳上报 cpuLoad=NaN | `HeartbeatRequest` compact constructor 拒绝（`Double.isFinite`）→ 400 |
| E3 | 心跳上报给未注册的 nodeId | `NodeRegistry.heartbeat` 抛 `NoSuchElementException` → 404 |
| E4 | deregister 已注销的 nodeId | `registry.deregister` 返回 `false` → 404 |
| E5 | sweep 时心跳并发赢了（CAS 失败） | `nodes.replace` 返回 false，`applySweepTransition` 返回 0，状态不变 |
| E6 | schedule 无合格节点 | `RuntimeScheduler.schedule` 抛 `NoSuchElementException` → 404 |
| E7 | preferredNodeId 指向的节点不具备所需能力 | 降级到 round-robin 在 ACTIVE∩capabilities 节点间选 |
| E8 | 全部节点 STALE | `listAll` 返回含 STALE 节点；`RuntimeRoutes` 的 `/runtimes` 和 `/capabilities` 只看 ACTIVE |

## 6. 性能 (DFX)

| 维度 | 当前状态 | 后续 |
|---|---|---|
| 注册表并发 | `ConcurrentHashMap` + `computeIfPresent` / `replace` CAS | 生产规模够用 |
| sweep 延迟 | 每 10s 一次，遍历全量节点（O(n)） | n > 1000 时考虑分片 |
| 调度策略 | AtomicInteger round-robin，O(candidates) | 后续可插拔策略链（least-active / weighted） |
| 持久化 | 无（内存）；重启丢全部节点 | [DEF-003](../DEFERRED.md)（etcd / Postgres） |
| 可观测 | SLF4J log.info/warn/debug | 后续接 micrometer counter/gauge |

## 7. 契约改动

### HTTP API（新增）

| Method | Path | Body | Status | 说明 |
|---|---|---|---|---|
| POST | `/api/v1/nodes` | `RegisterNodeRequest` | 201 | 注册节点 |
| POST | `/api/v1/nodes/{id}/heartbeat` | `HeartbeatRequest` | 200 | 心跳 |
| GET | `/api/v1/nodes` | — | 200 | 列表 |
| GET | `/api/v1/nodes/{id}` | — | 200 / 404 | 单个 |
| DELETE | `/api/v1/nodes/{id}` | — | 204 / 404 | 注销 |
| GET | `/api/v1/runtimes` | — | 200 | ACTIVE 节点列表（RuntimeView） |
| GET | `/api/v1/runtimes/capabilities` | — | 200 | ACTIVE 能力并集 |
| POST | `/api/v1/runtimes/schedule` | `ScheduleRequestBody` | 200 / 404 | 调度决策 |

### application.yml（新增段落）

```yaml
controlplane:
  heartbeat:
    ttl: PT30S
    sweep-interval: PT10S
    grace-after-stale: PT5M
```

## 8. 测试

| 测试类 | 模块 | 覆盖 |
|---|---|---|
| `NodeInfoTest` | agent-core | blank id / port 范围 / null 必填 / withStatus 转换（4 个） |
| `NodeMetricsTest` | agent-core | 负值 / NaN / ±Infinity（1 个） |
| `ControlPlanePropertiesBindingTest` | agent-core | yaml binding override + missing 块默认值（2 个，用 `ApplicationContextRunner`） |
| `NodeRegistryTest` | agent-core | register / heartbeat / sweep ACTIVE→STALE / sweep STALE→remove / deregister 幂等（6 个，用 `MutableClock`） |
| `RuntimeSchedulerTest` | agent-core | schedule 选合格 / affinity / 优先节点无能力回落 / 无候选（4 个） |
| `NodeRoutesTest` | coding-agent-cli | register→list / heartbeat / 404 / 400 / deregister 204+404（5 个，用 `WebTestClient.bindToRouterFunction`） |
| `RuntimeRoutesTest` | coding-agent-cli | capabilities 并集 / 列表 / schedule / affinity / 404（5 个） |

## 9. 验证

- `./mvnw -B verify`：6 模块 BUILD SUCCESS，1310 tests 全过
- `./mvnw -f mate-campusclaw/pom.xml -B clean test`：2834 tests + coverage checks met
- `./scripts/sync-mate-campusclaw.sh`：mate 镜像自动同步 controlplane 包，编译通过
- checkstyle 0 violations（含 JavadocMethod / Javadoc 完整性 / CC ≤ 20 / NBNC ≤ 50）

## 10. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.1 | 2026-08-19 | 移除已废弃的 Docker Sandbox 能力枚举说明 |
