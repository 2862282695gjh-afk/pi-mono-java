# Coding Agent Runtime HTTP 与受管 Session 设计

> 文档版本：3.3.0
>
> PR 167 修订基线：`f60cc3e78bb8b700527ac082c7c8e10524ede095`
>
> PR 167 merge base：`d649866a6cae967ace18ceaeb9597edd47e5721e`
>
> HTTP 1.39 设计输入：`superheromeZzh/pi-mono-java-design@3fde5735cd27433c3e3e5e03a5ce39b297ad3b00`
>
> 流式预览术语修订基线：`28b3235e5cff0da2f768cbfc6b7b9ce5e2b51193`
>
> 源码仓库：本仓库 `pi-mono-java`

## 1. 结论

`campusclaw-agent.jar` 只有 Spring Boot 服务启动方式，不再分发 CLI/TUI 产品模式。
Runtime HTTP、Cron trigger 和 Child Execution 共同使用 `AgentSessionFactory`，完整工具和目录
契约见[工具系统 v2](tool-system-v2.md)。

历史 `--mode server`、手工 Reactor Netty `ServerMode`、函数式 WebFlux 路由和公开 WebSocket 接口均已删除。Runtime 对外协议统一为 HTTP + 请求范围 SSE。

Runtime HTTP 现已在 1.38 lowerCamelCase 契约上实现 1.39 修订：Session 增加生命周期 Usage，
Assistant/Compaction 完成保存本次 Usage，模型/思考/压缩形成持久化领域事件，工具 delta 与
压缩 started/failed 是只在当前 SSE 中发送的流式预览事件，不持久化、不进入 GET Events。
资源与 thinking 决策见
[ADR-0018](../decisions/0018-runtime-http-v137-contract-alignment.html)，字段命名边界见
[ADR-0019](../decisions/0019-runtime-http-lower-camel-case-fields.html)，TUI 能力迁移见
[ADR-0023](../decisions/0023-retain-entry-independent-session-capabilities.html)。

## 2. 源码证据

| 事实 | 源码位置与符号 |
|---|---|
| 默认启动 Spring Boot Web 应用 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/CampusClawApplication.java`，`CampusClawApplication#main` |
| 三入口公共 Session 装配 | `session/AgentSessionFactory.java`、`ManagedAgentSession.java` |
| HTTP 创建前准备受管目录 | `runtimeapi/runtime/RuntimeSessionEngineRegistry.java`、`runtime/AgentRuntimeManager.java` |
| Runtime 使用 Spring MVC Controller | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/web/*Controller.java` |
| Runtime 不安装入站认证拦截器 | `runtimeapi/web` 不再包含 `RuntimeAuthenticationInterceptor` 与 `RuntimeWebMvcConfiguration`；路由测试覆盖 Header 缺失与共存 |
| 类型化资源 ID 与 Session 默认值 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/common/identifier/ResourceIdentifierPatterns.java`、`runtimeapi/web/*Controller` 的 `@PathVariable` 参数约束、`RuntimeExceptionHandler#handleInvalidParameter`、`MateServiceClient#getAgentRuntime`、`MateServiceClient#querySkillInfo`、`AgentRuntimeManager#prepare`、`HttpMateToolClient#listTools`、`RandomSessionIdGenerator#nextId`、`RuntimeSessionService#newSession` |
| lowerCamelCase HTTP 边界 | `runtimeapi/web/*Controller`、`runtimeapi/vo/*RequestVO`、`runtimeapi/vo/*ResponseVO`、`RuntimeEntryCodec#toSseData`、`RuntimeEntryCodec#toHistoryEvent`、`RuntimeEventProjector` |
| Session 与事件持久化使用 MyBatis | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/persistence/MyBatisRuntimeSessionRepository.java` |
| 事件接受、历史查询和执行生命周期相互分离 | `RuntimeEventService`、`RuntimeEventQueryService`、`RuntimeExecutionCoordinator` |
| thinking 实时投影、持久化和查询过滤 | `RuntimeEventProjector#projectThinking`、`RuntimeEntryCodec#thinkingEntry`、`RuntimeEventQueryService#list`、`RuntimeEventCursorCodec` |
| SSE 使用有界请求级订阅 | `RuntimeEventStream`、`RuntimeSseDispatcher`、`RuntimeSseEmitterSubscriber` |
| 公司响应包装保留适配点 | `runtimeapi/result/ResultBeanAdapter.java`、`StandaloneResultBeanAdapter.java` |
| 国际化资源显式区分两个 Locale | `modules/coding-agent-cli/src/main/resources/i18n/messages_{en_US,zh_CN}.properties`、`RuntimeMessageSourceConfiguration` |
| 语言选择按范围和权重协商 | `RuntimeRequestContext#locale`、`RuntimeRequestContext#language` |
| HTTP 与 SSE 错误通过 MessageSource 取文案 | `RuntimeExceptionHandler#response`、`RuntimeTerminalEventFactory#emitError` |
| 内置工具由关闭枚举和 profile 装配 | `tool/builtin/BuiltInToolName.java`、`BuiltInToolProperties.java`、`DefaultConfiguredToolAssembler.java` |
| MateService 工具通过专用客户端查询和调用 | `common/client/mate/MateToolClient.java`、`tool/mate/ListMateToolsTool.java`、`CallMateTool.java` |

表中的历史协议清理是上述提交基线的已观察行为；服务单入口和公共 SessionFactory 是相对
基线的架构改造，并已在当前分支实现。它们不表示 pi 已存在相同行为。

## 3. 模块上下文

![Coding Agent 模块上下文](coding-agent-cli/module-context.svg)

[PlantUML 源码](coding-agent-cli/diagram.puml#L1)

Spring MVC 只负责 HTTP 业务边界、资源校验、国际化和 SSE 连接，不在 Runtime 内认证集成 Header。会话执行复用 `agent-core` 的 Agent 循环，模型调用复用 `ai` 模块；控制面与 Runtime V1 共享同一 Spring Boot 进程，但路径和业务模型相互独立。

## 4. 服务启动

```bash
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar
```

默认监听 `0.0.0.0:8080`，可通过标准 Spring Boot 配置（例如 `SERVER_PORT`）覆盖。服务使用 Java 21 虚拟线程承载阻塞式 Spring MVC 请求；数据库访问和 Controller 均为阻塞式模型。

命令行参数不再切换另一套 Spring 上下文。CLI/TUI、Picocli 启动、终端 Session 持久化和
本地认证设置源码已删除；Slash Command 核心及 `/model`、`/thinking`、`/compact`、`/name`
处理器保留为未注册的宿主无关代码，不形成 Spring Bean、HTTP 接口或消息拦截器。

## 5. Runtime HTTP 结构

![Runtime HTTP 组件](coding-agent-cli/runtime-http-components.svg)

[PlantUML 源码](coding-agent-cli/diagram.puml#L32)

Runtime V1 固定前缀为 `/campusclaw-service/v1`，包含 11 个接口：

| 序号 | 方法与路径 | 作用 |
|---:|---|---|
| 1 | `POST /agents/{agentId}/sessions` | 创建 Session |
| 2 | `GET /sessions/{sessionId}` | 读取精简 Session 状态 |
| 3 | `DELETE /sessions/{sessionId}` | 幂等逻辑删除并创建清理任务 |
| 4 | `POST /sessions/{sessionId}/events` | 接收用户消息并以 SSE 返回本轮事件 |
| 5 | `GET /sessions/{sessionId}/events` | 以不透明游标读取当前分支对话 Entry |
| 6 | `GET /sessions/{sessionId}/models` | 返回当前模型和可用模型 ID 字符串数组 |
| 7 | `PUT /sessions/{sessionId}/model` | 通过强 ETag 切换后续消息使用的模型 |
| 8 | `PUT /sessions/{sessionId}/thinking` | 通过强 ETag 开启或关闭深度思考 |
| 9 | `POST /sessions/{sessionId}/steers` | 将高优先级补充消息加入活动执行 |
| 10 | `POST /sessions/{sessionId}/follow-ups` | 将普通后续消息按 FIFO 加入活动执行 |
| 11 | `POST /sessions/{sessionId}/abort` | 中止活动执行并清空未投递控制消息 |

权威、可交互的字段级契约维护在独立设计仓库的 HTML 文档中。本仓不再维护 OpenAPI 副本，避免出现两份互相漂移的契约。

## 6. 关键实现决策

### 6.1 鉴权边界

全部 11 项 Runtime operation 的集成契约均携带以下调用上下文 Header：

- 首选：`X-HW-ID` + `Authorization`；
- 兼容：`X-HW-ID` + `X-HW-APPKEY`。

这些 Header 由上游集成链路负责提供。Runtime 不在进入 Controller 前安装认证拦截器，也不在 Controller 内检查是否齐全、是否共存、Bearer 形状或凭据真实性；两种凭据 Header 同时出现也不会触发本地拒绝。凭据真实性与动作授权由上游 `mate-service` 完成；Header 不进入 Session、Prompt 或 Event，`X-HW-ID` 也不是 Session owner。Runtime 因而不定义 `UNAUTHENTICATED` 或 `AUTH_CREDENTIAL_CONFLICT`。这是认证边界架构变更，而不是取消集成 Header 契约，详见 [ADR-0018](../decisions/0018-runtime-http-v137-contract-alignment.html)。

### 6.2 响应包装

普通成功响应由 `ResultBeanAdapter` 生成 `resCode`、`resMsg`、`result`；错误响应只有 `resCode`、`resMsg`；204 和 SSE 不包装。独立仓提供 `StandaloneResultBeanAdapter`。公司工程接入时以 Bean 替换该适配器，并在该实现内调用公司真实的 `ResultBeanFactory.getFactory().normal()`，无需修改 Controller 或 Service。

### 6.3 SSE 生命周期

一次 `POST /events` 对应一个请求范围 SSE 连接。服务发出 `stream.end` 后关闭连接；下一次用户消息重新建立连接。Steer 与 FollowUp 在当前执行仍活动时进入其队列，事件继续从原 SSE 输出。客户端断线、订阅缓冲溢出只分离订阅，不中止 Agent。

请求体只接受 `message` 与 `fileIds`，路径已经固定 `user.message` 语义，旧 `type` 字段作为未知字段返回 `INVALID_EVENT_REQUEST`。执行接受时固化 Session 的 thinking 值：快照为 `true` 时投影 `assistant.thinking.started/delta/completed`，其中 completed 作为独立 Entry 持久化；快照为 `false` 时不产生 thinking 事件。Assistant MessageEntry 本身仍过滤 `ThinkingContent`，防止同一内容重复进入消息正文。

`GET /events` 每次读取 Session 当前 thinking，返回公共消息、模型/思考变更、压缩完成和允许
显示的 thinking。Agent 上下文恢复使用独立查询，只消费消息、工具结果和最新压缩边界，不把
模型或思考变更事件转换为模型消息。thinking 为 `false` 时只隐藏
`assistant.thinking.completed`，不删除数据库记录。加密 page 同时绑定 `session_id`、继续位置、
thinking 状态和过期时间；开关变化后旧 page 返回 `INVALID_EVENT_LIST_QUERY`。

Assistant 完成与压缩完成都持久化完整 `Usage`；`t_session_materialized` 在同一事务中原子累计
`lifetimeUsage`。Token 字段为 `input/output/cacheRead/cacheWrite/totalTokens`，USD Cost 字段为
`input/output/cacheRead/cacheWrite/total`。工具进度只通过非持久化的 `tool.execution.delta` 作为流式预览发送，
数据仅含 `toolCallId/toolName/delta`；不可序列化、超限或背压时只丢弃该进度，不影响工具执行和最终结果。
工具执行的持久化 `tool.result` 才是可恢复的权威结果。

每个订阅的缓冲限制为 256 个事件或 1 MiB，心跳间隔 15 秒。执行上限为 100 个，默认 30 分钟超时。

### 6.4 数据和 Agent 目录

Session、Entry、严格序号、物化数据、删除墓碑和异步清理任务持久化到 openGauss。删除活动 Session 返回 409；成功删除的墓碑只包含 `session_id` 与 `deleted_at`。

Agent、Tool、Skill 和 Session ID 分别匹配 `agent-`、`tool-`、`skill-`、`session-` 加 32 位十六进制 UUID（UUID 内部连字符已移除）。四类资源 ID 的正则字符串与编译后的 `Pattern` 统一由中立的 `common.identifier.ResourceIdentifierPatterns` 提供；业务类不重复编译，也不依赖 HTTP 专用常量类。HTTP 路径中的 Agent 与 Session ID 直接在 Controller 的标量 `@PathVariable` 参数上使用 Jakarta `@NotBlank` 和 `@Pattern`，Spring MVC 方法参数校验失败后由 `RuntimeExceptionHandler` 映射为稳定错误码，不再维护命令式路径 ID Validator。`RandomSessionIdGenerator` 只生成该 Session 格式；创建 Session 持久化 `thinking=true`，默认模型不支持 reasoning 时按无有效默认模型返回 `AGENT_MODEL_NOT_CONFIGURED`，避免对外状态与实际事件能力不一致。`t_sessions.agent_id` 使用 `VARCHAR(64)`，可容纳完整类型化 Agent ID。

Agent 配置由 `AgentRuntimeManager.prepare(agentId)` 准备到
`agent/{agentId}/.campusclaw/`；部署可通过 `CAMPUSCLAW_AGENT_ROOT` 替换 `agent` 根目录。
Session 的受控工作区是整个 `agent/{agentId}`，`Read`、`Find`、`Grep`、`Ls` 共享该边界并
拒绝符号链接和 realpath 越界。Runtime 使用工具系统 v2 的 `runtime` profile，而不是历史的
单一 `read` 工具。`fileIds` 作为固定 `[File IDs]` 提示块传入，不在 Runtime 内解析或下载文件。

### 6.5 HTTP 字段命名边界

公开 Path、Query、JSON 请求、普通 JSON 响应和 SSE `data` 字段统一使用 lowerCamelCase；`X-HW-ID`、`Authorization`、`X-HW-APPKEY`、`If-Match`、`Accept-Language` 等 Header 保持原名，事件类型、错误码、`tool_call` 等枚举值和类型化 ID 值也不改变。请求 VO 不接受 snake_case 别名。

数据库列、MyBatis 映射与既有 Entry payload 属于内部持久化格式，可以继续使用 snake_case。`RuntimeEntryCodec` 对既有 payload 做字段级受控投影后再输出 lowerCamelCase；工具 `arguments` 内的键由工具 Schema 所有，不参与 Runtime 字段改名。该边界避免数据库迁移和历史 Entry 失效，同时阻止内部格式泄露到 HTTP 契约。

### 6.6 事件执行职责

`RuntimeEventService` 只负责接受 `user.message` 和提交前的原子边界；
`RuntimeEventQueryService` 负责当前分支分页与 Agent 历史恢复；
`RuntimeExecutionCoordinator` 负责 Agent 启动、控制消息续跑、超时、持久化收尾和资源释放。
SSE 流、事件投影器与终止事件分别由独立工厂创建，避免 Controller 或单个 Service 同时承担完整执行生命周期。

管理面 refresh 不修改活动执行快照。空闲 Session 下一次执行时由
`RuntimeSessionModelReconciler` 读取最新目录；当前模型失效时，先原子持久化模型及必要 thinking
变更事件，再接受 `user.message`。无具备凭据的 default 时直接拒绝，数据库中不产生用户 Entry。

公共 `ManagedAgentSession` 持有阈值压缩、上下文溢出压缩和最多一次重试。压缩失败保留旧消息；
成功压缩持久化完整摘要和保留边界。配置默认 `enabled=true`、`reserveTokens=16384`、
`keepRecentTokens=20000`，文件追踪只识别 `Read`。

### 6.7 错误和多实例边界

`RuntimeErrorCode` 是错误码、HTTP 状态、国际化 key 和可选 `Retry-After` 的唯一目录。
错误消息资源 key 与枚举名称一致，异常调用点不能自行拼装 HTTP 状态。

活动执行仍是进程内资源。如果数据库状态为 `running`，但 Steer、FollowUp 或 Abort 请求没有命中执行实例，
服务返回 `503 SESSION_EXECUTION_UNAVAILABLE` 和 `Retry-After: 3`。这是对现有执行归属边界的显式表达；
本次整改没有假设粘性路由或跨实例转发基础设施。

### 6.8 国际化资源与语言协商

Runtime 只保留以下两个显式 Locale 资源包：

```text
modules/coding-agent-cli/src/main/resources/i18n/messages_en_US.properties
modules/coding-agent-cli/src/main/resources/i18n/messages_zh_CN.properties
```

`mate-campusclaw` 镜像使用相同的 `src/main/resources/i18n/` 相对路径。实现不创建
`messages.properties`。由于 Spring Boot 的默认消息源自动配置要求基础资源包，Runtime
必须通过独立配置显式注册名称为 `messageSource` 的 `ResourceBundleMessageSource`：
basename 固定为 `i18n/messages`，编码固定为 UTF-8，默认 Locale 为 `Locale.US`，并关闭
系统 Locale 回退。该配置只属于 Runtime HTTP 包。

Runtime 只支持 `en-US` 和 `zh-CN`。语言协商必须按 `Accept-Language` 标准语义处理语言
范围与 `q` 权重，并把结果收敛为 `Locale.US` 或 `Locale.SIMPLIFIED_CHINESE`；请求头缺失、
非法或没有支持项时统一回退 `en-US`。错误 HTTP 响应和 SSE `stream.error` 事件使用协商后的
Locale 获取 `resMsg`，HTTP `Content-Language` 返回实际语言。成功 ResultBean 保持固定
`resCode="0"`、`resMsg="success"`，不进入消息资源。

两个 Locale 文件必须具有完全相同的 key 集合，并与 `RuntimeErrorCode` 枚举名称一致；
缺键属于构建和发布阻断问题，不通过 `resMsg` 或错误码字符串兜底。该资源布局不改变 HTTP
状态、`resCode`、响应结构或 SSE 事件结构，分类为内部架构变更。决策依据见
[ADR-0017](../decisions/0017-explicit-locale-message-bundles.html)。

### 6.9 工具所有权与 Agent 边界

Runtime 只公开工具系统 v2 的八个名称。`Bash`、`Edit`、`Write`、`Loop` 和动态
ToolCatalog/Extension 不进入模型工具列表。`Read`、`Find`、`Grep`、`Ls` 只能读取当前
`agent/{agentId}`，且拒绝符号链接和 realpath 越界；Mate 工具由 MateService 最终授权执行。

Runtime V1 事件名 `tool.execution.started` 与 `tool.execution.completed` 是 HTTP/SSE
事件类型，不是工具配置项，必须继续保留。完整契约见[工具系统 v2](tool-system-v2.md)和
[ADR-0022](../decisions/0022-managed-agent-tool-system-v2.html)。

## 7. 质量约束

- Controller 只接收和返回 VO，Service 负责业务规则和 VO/DTO 转换，Mapper 使用 DTO；
- 请求 VO 使用 Jakarta Bean Validation；
- MyBatis Mapper XML 使用 `resultType`，全局启用下划线到驼峰映射；
- 新增或修改的 Java 方法不超过 50 个非空物理行；
- Java 与 XML 源文件遵循公司版权、中文 Javadoc 和 XML DTD 规则；
- 主模块与 `mate-campusclaw` 镜像必须通过同一套测试。
- 国际化实现必须验证无基础资源包时应用上下文可启动、双资源 key 集相等且覆盖
  `RuntimeErrorCode`，并覆盖语言权重、英文回退、HTTP 中文错误和 SSE 中文错误。

## 8. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 3.3.0 | 2026-08-28 | 统一 Runtime Event 术语：将 started/delta/progress 和压缩 started/failed 称为“流式预览事件”，明确它们只在当前 SSE 中发送、不持久化、不进入 GET Events；对齐 `RuntimeEventProjector` 的直接 stream emit 与 `RuntimeEventQueryService` 只查询持久化 Entry 的已实现行为，不改变 Java 代码或线上契约。 |
| 3.2.0 | 2026-08-24 | 修订 TUI 删除边界，保留未注册 Slash 核心；公共 Session 增加压缩，Runtime 增加 Usage、领域事件、best-effort 工具进度与 refresh 后懒校准 |
| 3.1.0 | 2026-08-24 | 删除 CLI/TUI、Picocli、终端 Session、用户级认证设置与启动脚本源码，服务模型目录仅使用内置注册表和部署凭据 |
| 3.0.0 | 2026-08-24 | 删除 CLI 产品入口；HTTP、Cron、Child 共用 SessionFactory；Runtime 创建前 prepare 受管目录并装配八工具 profile |
| 2.7.0 | 2026-08-21 | 对齐 Runtime HTTP 1.38：公开 Path、Query、JSON 与 SSE data 字段统一为 lowerCamelCase；Header 和字段值保持原样；既有 Entry payload 在输出边界受控投影 |
| 2.6.2 | 2026-08-21 | 将 HTTP 路径 ID 校验改为 Controller 标量参数的 Jakarta 注解，并集中映射方法校验错误 |
| 2.6.1 | 2026-08-21 | 集中类型化资源 ID 的正则字符串和编译模式，分离领域约束与 Runtime HTTP 常量 |
| 2.6.0 | 2026-08-20 | 对齐 Runtime HTTP 1.37：类型化资源 ID、Session 默认 thinking、无本地 Header 认证、精简消息体及 thinking 事件可见性和游标绑定 |
| 2.5.0 | 2026-08-20 | 整合并行设计更新，保留双 Locale 国际化和 macOS/Linux 平台收敛，并消除 ADR 编号冲突 |
| 2.4.0 | 2026-08-20 | 分别明确无基础资源包的国际化边界，以及仅维护 macOS/Linux 的启动平台边界 |
| 2.3.0 | 2026-08-19 | 合入最新 HTTP V1 启动与执行架构，并明确 ToolCatalog、MateService 工具和已删除本地 Sandbox 的边界 |
| 2.2.0 | 2026-08-19 | 统一 `.campusclaw` 真实运行根目录，拆分事件职责，集中错误语义并明确非本机执行边界 |
| 2.1.0 | 2026-08-19 | HTTP V1 的 Agent 根目录默认值改为 `agent`，受控子目录改为 `.campusclaw/` |
| 2.0.0 | 2026-08-18 | 按实现提交 `8691e880` 重写；默认 Spring MVC 服务、显式 CLI、HTTP+SSE 和 11 个 Runtime 接口成为现行设计 |
| 1.x | 2026-08-18 以前 | 历史 ServerMode、WebFlux RouterFunction 与公开 WebSocket 设计，已废弃 |
