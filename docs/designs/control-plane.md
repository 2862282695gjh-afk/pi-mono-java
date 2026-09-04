# Control Plane 设计

> 文档版本：2.2.0
>
> 变更前基线：`0300541fbbe3db8b05ffa1ac953f15f01df74b3e`
>
> 已核对实现基线：`e8b861f2878ff07769a0e1c15ac5e45ab04316b3`

## 1. 现状

控制面已经从函数式 WebFlux 路由迁移为 Spring MVC `@RestController`，并随默认 Spring Boot HTTP 进程启动。它不是 CampusClaw Runtime V1 的 11 个业务接口；两者只共享进程和 Web 容器。

| 组件 | 源码证据 | 职责 |
|---|---|---|
| `StatusController.status` | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/controlplane/api/StatusController.java:19` | 返回固定文本，供调用方探测服务存活 |
| `NodeController` | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/controlplane/api/NodeController.java` | 注册、心跳、查询和注销数据面节点 |
| `RuntimeController` | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/controlplane/api/RuntimeController.java` | 汇总活动 Runtime、能力和调度决策 |
| `NodeRegistry` | `modules/agent-core/src/main/java/com/campusclaw/agent/controlplane/service/NodeRegistry.java` | 维护进程内节点状态 |
| `RuntimeScheduler` | `modules/agent-core/src/main/java/com/campusclaw/agent/controlplane/service/RuntimeScheduler.java` | 按能力筛选，优先首选节点，否则轮询选择 |
| `ControlPlaneExceptionHandler` | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/controlplane/error/ControlPlaneExceptionHandler.java` | 映射稳定的控制面错误响应 |

上述为实现基线的已观察行为。

变更前基线尚无 `StatusController`；该类首次出现在上述已核对实现基线。
`CampusClawApplication` 的 `@SpringBootApplication(scanBasePackages = "com.campusclaw")`
覆盖其所在包，文件位于 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/CampusClawApplication.java`。
企业镜像由 `scripts/sync-campusclaw.sh` 生成对应的
`campusclaw/src/main/java/com/huawei/hicampus/claw/codingagent/controlplane/api/StatusController.java`。

## 2. 组件关系

![Control Plane 组件关系](control-plane/components.svg)

[PlantUML 源码](control-plane/diagram.puml#L1)

## 3. HTTP 接口

### Status

用户提供的代码要求新增固定响应的服务存活接口，供探测方确认 HTTP 请求能够到达服务。

| 方法 | 路径 | 输入 | 成功结果 |
|---|---|---|---|
| `GET` | `/api/v1/status` | 无必填参数、请求体或凭据 Header | `200 OK`，响应体为 `Success` |

**已观察行为**：Spring MVC 将请求交给 `StatusController.status()`，方法直接返回
`ResponseEntity.ok("Success")`；普通请求得到纯文本响应。该方法没有注入依赖、可变状态或外部 I/O，
不会访问节点注册表、数据库、MateService 或模型服务；接口本身的处理时间和空间均为 O(1)。

**设计决策与理由**：保留截图指定的路径、大小写和字符串响应，不增加 JSON/ResultBean 或 VO 包装。
这是明确的**产品约束**，详见 [ADR-0052](../decisions/0052-service-status-endpoint.md)。
成功仅表示当前进程能够处理该 HTTP 请求，不代表外部依赖健康、会话可执行或服务已具备业务就绪条件。
依赖故障不会被该方法主动检测；进程未启动或请求未到达时也无法保证返回 `Success`。
本次没有引入独立安全加固或其他架构变更。

### Node

| 方法 | 路径 | 结果 |
|---|---|---|
| `POST` | `/api/v1/nodes` | 注册节点，返回 201 与 `Location` |
| `POST` | `/api/v1/nodes/{nodeId}/heartbeat` | 更新指标并返回节点快照 |
| `GET` | `/api/v1/nodes` | 返回全部节点 |
| `GET` | `/api/v1/nodes/{nodeId}` | 返回单个节点或 404 |
| `DELETE` | `/api/v1/nodes/{nodeId}` | 注销节点，返回 204 或 404 |

### Runtime

| 方法 | 路径 | 结果 |
|---|---|---|
| `GET` | `/api/v1/runtimes` | 返回状态为 ACTIVE 的节点 |
| `GET` | `/api/v1/runtimes/capabilities` | 返回活动节点能力并集 |
| `POST` | `/api/v1/runtimes/schedule` | 根据必需能力和首选节点返回调度决策 |

Node 和 Runtime 的请求对象使用 Jakarta Bean Validation；输出使用专用 Response VO。控制面暂时保留自身错误结构，不复用 Runtime V1 的 ResultBean，这是既有控制面兼容性约束。

`RuntimeCapability` 保留模型、本地 Bash/文件、ACP/HTTP/A2A/MCP 子 Agent 等粗粒度能力；
本地 Docker Sandbox 能力已经删除，不再参与节点注册和调度。

## 4. 生命周期与并发

`NodeRegistry` 是进程内状态源。注册产生节点 ID；心跳更新指标与时间；健康检查任务将超时节点标记为不可用。`RuntimeScheduler` 只在活动节点中筛选，先满足能力约束，再应用首选节点和轮询规则。

本设计没有把控制面状态持久化到 openGauss。因此进程重启后节点必须重新注册。这是当前实现事实，不应解释为持久化控制平面。

## 5. 安全边界

当前控制面端点没有认证或授权，而默认 HTTP 服务监听 `0.0.0.0`。这不是安全完成态，而是明确的安全债务；在生产网络暴露这些 `/api/v1/*` 路径前，必须由网关隔离，或补充与部署体系匹配的认证和授权。

`StatusController.status()` 不读取或校验业务凭据 Header。历史“仅绑定 localhost，因此可以延期鉴权”的 ADR 已因启动模型变化而删除。

## 6. 验证

`NodeControllerTest` 和 `RuntimeControllerTest` 使用 MVC 测试覆盖成功、校验、404 和调度失败映射。`NodeRegistryTest`、`HealthCheckSchedulerTest` 与 `RuntimeSchedulerTest` 覆盖领域行为。

2026-09-04 状态接口首次实现验证：

- JDK 21 下运行 `./mvnw -q spotless:apply checkstyle:check`，通过。
- 运行 `./mvnw -q -pl :campusclaw-coding-agent -am -Dtest=NodeControllerTest,RuntimeControllerTest -Dsurefire.failIfNoSpecifiedTests=false package`，打包及现有 6 个控制面测试通过。
- 通过 JShell 调用 MockMvc 发起无凭据的 `GET /api/v1/status` 冒烟请求，观察到 HTTP 200、`text/plain`、响应体精确为 `Success`。本次未新增单元测试；该检查不等同于完整部署进程测试。
- `./scripts/sync-campusclaw.sh` 因无法解析 `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT` 失败；按本地环境流程运行 `--no-verify` 完成源码同步。企业父 POM 下的完整镜像编译仍待具备公司 Maven 仓库访问条件的环境执行。
- 镜像 dry-run 及 502 个生成 Java 文件的逐字节一致性检查通过；最终 `spotless:check`、Checkstyle、PlantUML 生成与 ASCII 检查、SVG XML 与重复生成一致性、修改文档的本地链接和行锚点、仓库 Markdown 的 Mermaid 禁用检查及 `git diff --check` 均通过。

2026-09-04 测试补充：

- 基于已合入主干 `0c555642b0712dbaab21bb9312823f33cdf8e6a7` 的分支基线 `625f14e44dc93727382463c6a12c7ad646bef4e6`，新增 `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/controlplane/api/StatusControllerTest.java`。`statusReturnsOkWithSuccessBody()` 直接调用 `StatusController.status()`，分别断言 `HttpStatus.OK` 与响应体 `Success`。
- 运行 `./mvnw -q -pl :campusclaw-coding-agent -am -Dtest=StatusControllerTest,NodeControllerTest,RuntimeControllerTest -Dsurefire.failIfNoSpecifiedTests=false package`，新增 1 个测试与既有 6 个测试全部通过，编译打包通过。
- Spotless 与 Checkstyle 通过。企业镜像测试由同步脚本生成；完整企业镜像编译仍因 `NativeParent` 不可解析而受阻。
- `CLAUDE.md` 要求的 `~/.claude/skills/java-ut-coverage-loop/scripts/check_test_quality.py` 在本机缺失，调用失败，未完成该工具检查；已核对新增用例的两个断言均检查实际方法返回值。
- 主干已使用 ADR-0050 与 ADR-0051，因此将状态接口记录重编号为 ADR-0052，并同步引用；接口契约及组件关系不变。

## 7. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 2.2.0 | 2026-09-04 | 新增固定响应的服务存活接口，记录实现基线、产品约束与验证结果，并按当前源码澄清调度规则；后续补充 StatusController 单元测试，并在合入最新主干后将本接口 ADR 编号调整为 0052，接口契约不变 |
| 2.1.0 | 2026-08-19 | 对齐最新主干并删除本地 Docker Sandbox 能力枚举说明 |
| 2.0.0 | 2026-08-18 | 对齐 Spring MVC Controller 与默认 Web 进程，删除 WebFlux RouterFunction 和 ServerMode ADR |
| 1.x | 2026-06-22 | 历史函数式 WebFlux 控制面设计，已废弃 |
