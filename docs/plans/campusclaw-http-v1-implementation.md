# CampusClaw HTTP V1 实施记录

> 版本：3.0.0
>
> 状态：已实现并完成发布前验证
>
> Runtime HTTP 1.37 对齐分析基线：`3d9b9c55569486f024d6a507ce004101a56dee3f`
>
> 国际化实现起点：`3a6358bc9dd5837cdf5ac866fc0761298372510a`
>
> 初始实现提交：`8691e8800f05f28afe22499050c29220ef5b7475`
>
> 日期：2026-08-18

## 1. 目标与边界

本次把 CampusClaw 从“CLI 默认启动、手工 ServerMode 提供旧接口”的模型收口为：

- 默认 `java -jar` 启动 Spring Boot MVC HTTP 服务；
- `java -jar ... cli` 显式进入 CLI；
- Runtime 对外统一为 HTTP + 请求范围 SSE；
- 按已确认契约实现 11 个 Session 接口；
- openGauss 作为 Session/Entry 持久化源；
- `mate-campusclaw` 由同步脚本生成并验证；
- 删除旧公开 WebSocket、ServerMode、WebFlux 路由和本仓 OpenAPI 副本。

公司内部 ResultBean 制品坐标和真实类全限定名未提供，因此没有臆造依赖。实现通过 `ResultBeanAdapter` 保留替换点；公司 Bean 在集成工程中调用真实 `ResultBeanFactory.getFactory().normal()` 即可获得相同 Controller 开发体验。

## 2. 源码证据

| 领域 | 实现路径与符号 |
|---|---|
| 启动 | `modules/coding-agent-cli/.../CampusClawApplication.java`、`CampusClawCliLauncher.java` |
| HTTP 边界 | `modules/coding-agent-cli/.../runtimeapi/web/*Controller.java` |
| 调用上下文 Header 边界 | Controller 路由测试；Runtime 不再包含认证器、认证拦截器或认证错误码 |
| 类型化资源 ID | `RuntimeApiConstants`、`MateServiceClient`、`AgentRuntimeManager`、`HttpMateToolClient`、`RandomSessionIdGenerator` |
| ResultBean / i18n | `runtimeapi/result/*`、`RuntimeMessageSourceConfiguration`、`RuntimeRequestContext`、`src/main/resources/i18n/messages_{en_US,zh_CN}.properties` |
| Session 业务 | `runtimeapi/session/RuntimeSessionService.java`、`RuntimeSessionConfigurationService.java`、`RuntimeSessionControlService.java` |
| 事件接受 | `runtimeapi/event/RuntimeEventService.java`、`RuntimeExecutionContextFactory.java` |
| thinking 事件投影 | `runtimeapi/event/RuntimeEventProjector.java`、`RuntimeEntryCodec#thinkingEntry` |
| 事件查询 | `runtimeapi/event/RuntimeEventQueryService.java`、`RuntimeEventCursorCodec.java`、`RuntimeSessionMapper.xml#listCurrentBranch` |
| 执行协调与 SSE | `RuntimeExecutionCoordinator.java`、`RuntimeEventStream.java`、`RuntimeSseDispatcher.java` |
| 执行生命周期 | `runtimeapi/runtime/RuntimeSessionEngineRegistry.java`、`RuntimeActiveExecution.java`、`RuntimeExecutionTimeoutScheduler.java` |
| 持久化 | `runtimeapi/persistence/MyBatisRuntimeSessionRepository.java`、`mapper/session/RuntimeSessionMapper.xml` |
| DDL | `src/main/resources/db/gaussdb/install/session_schema.sql` |
| Agent 目录 | `runtimeapi/agent/FileAgentDirectoryResolver.java`、`RuntimeAgentPromptLoader.java` |
| 公司镜像 | `mate-campusclaw/`、`scripts/sync-mate-campusclaw.sh` |

## 3. 接口完成情况

| 序号 | 方法与路径 | 核心语义 | 结果 |
|---:|---|---|---|
| 1 | `POST /campusclaw-service/v1/agents/{agent_id}/sessions` | 生成类型化 Session ID，创建时初始化 `thinking=true` | 已实现 |
| 2 | `GET /campusclaw-service/v1/sessions/{session_id}` | 返回名称、state、model、thinking、版本等精简状态 | 已实现 |
| 3 | `DELETE /campusclaw-service/v1/sessions/{session_id}` | running 返回 409；idle 幂等删除；墓碑仅两字段 | 已实现 |
| 4 | `POST /campusclaw-service/v1/sessions/{session_id}/events` | 请求体只含 `message/file_ids`；按执行快照投影 thinking 并直接返回 SSE | 已实现 |
| 5 | `GET /campusclaw-service/v1/sessions/{session_id}/events` | 按当前 thinking 过滤持久化事件，page 绑定该状态 | 已实现 |
| 6 | `GET /campusclaw-service/v1/sessions/{session_id}/models` | `current_model_id` + 模型 ID 字符串数组 | 已实现 |
| 7 | `PUT /campusclaw-service/v1/sessions/{session_id}/model` | idle + 强 `If-Match`，同值不增版本 | 已实现 |
| 8 | `PUT /campusclaw-service/v1/sessions/{session_id}/thinking` | 布尔深度思考 + 强 `If-Match` | 已实现 |
| 9 | `POST /campusclaw-service/v1/sessions/{session_id}/steers` | running 时加入高优先级队列 | 已实现 |
| 10 | `POST /campusclaw-service/v1/sessions/{session_id}/follow-ups` | running 时加入 FIFO 队列 | 已实现 |
| 11 | `POST /campusclaw-service/v1/sessions/{session_id}/abort` | 幂等中止，清空未投递队列 | 已实现 |

## 4. 已观察行为、目标决策和差异分类

| 主题 | 源码基线已观察行为 | 最终实现 | 差异分类与理由 |
|---|---|---|---|
| Web 栈 | `spring.main.web-application-type=none`；`ServerMode` 手工 Reactor Netty | 默认 Spring MVC + 虚拟线程 | 架构变更：符合正常 Spring Boot 部署和公司开发习惯 |
| 执行建模 | pi 可通过 Entry 树恢复历史，没有公开 Run | 仍不公开 Run；活动执行只在服务内部存在 | 产品约束：调用方以 Session Events 区分轮次 |
| 流式连接 | 旧本地接口与公开 WebSocket 并存 | 单次 POST 建立 SSE，`stream.end` 后关闭 | 架构变更：协议唯一、断线可通过历史恢复 |
| Session 与模型 | CLI 启动时先选模型 | Session 创建不要求模型，可在后续事件前切换 | 产品约束：Session 生命周期允许模型切换 |
| 删除 | 历史方案曾计划自动 abort | active execution 返回 409；idle 才删除 | 安全加固：避免删除与执行副作用竞态 |
| 调用上下文 Header | 基线认证拦截器检查 Header 齐全、共存和 Bearer 形状 | 全接口保留集成 Header 契约，但 Runtime 不做本地检查 | 架构变更：真实性和动作授权由上游 mate-service 保证，Runtime 不建立凭据状态 |
| 资源 ID | Agent 使用下划线短 ID，Session 使用无类型 Crockford Base32 | Agent/Tool/Skill/Session 使用类型前缀加 32 位无连字符 UUID | 产品约束：阻止无前缀、错误类型和旧格式进入边界 |
| 创建默认值 | `RuntimeSessionService#newSession` 持久化 `thinking=false` | 创建时持久化 `thinking=true` | 产品约束：新 Session 默认启用深度思考 |
| 用户事件请求 | `UserEventRequestVO` 要求冗余 `type=user.message` | 只接受 `message` 与 `file_ids`，`type` 作为未知字段拒绝 | 产品约束：operation 已固定消息类型 |
| thinking 事件 | Provider thinking 事件未进入公共 Runtime SSE 或持久化历史 | 执行快照为 true 时开放三阶段事件并持久化 completed；GET 按当前状态过滤 | 架构变更：显式可见性策略和可恢复事件保持一致 |
| Agent 来源 | 曾设计独立模板快照 | 直接读取 Agent 目录，只启用 read 工具 | 架构变更：去掉重复模板仓库和缓存 |
| 文件 | 曾设计 Runtime 文件解析 port | `file_ids` 原样组成固定提示块 | 产品约束：文件内容不由 Runtime 下载 |
| Agent 运行根目录 | 解析器返回 Agent 父目录，提示词加载器再追加 `.campusclaw` | 解析器直接返回 `.campusclaw` 真实路径，并统一用于 `cwd`、提示词和 `ReadTool` | 安全加固：避免 HTTP V1 工具读取 Agent 父目录 |
| 事件业务职责 | 单个 `RuntimeEventService` 同时承担接受、分页、历史恢复和异步执行收尾 | 拆分接受、查询、上下文准备和执行协调 | 架构变更：降低构造依赖和修改影响面 |
| 错误语义 | 调用点分别指定 HTTP 状态和错误码 | 错误枚举集中状态、i18n key 与重试时间 | 安全加固：避免同一错误码出现不同 HTTP 语义 |
| 国际化资源与协商 | 根目录基础英文资源包加中文资源包；请求头只按 `zh-CN` 前缀判断 | 仅保留 `i18n/messages_en_US.properties` 与 `messages_zh_CN.properties`；显式消息源按标准语言范围和 `q` 权重协商 | 架构变更：支持 Locale、默认语言、编码和回退规则均显式，消除基础英文资源副本 |
| 多实例执行归属 | `running` Session 未命中本机 Registry 时落入通用 500 | 返回 `503 SESSION_EXECUTION_UNAVAILABLE` | 架构约束：明确需要路由到执行实例，但不臆造转发设施 |

## 5. 关键运行语义

### SSE

典型流式序列为：

```text
user.message
assistant.message.started
assistant.thinking.started       thinking=true 时可选
assistant.thinking.delta         thinking=true 时可选
assistant.thinking.completed     thinking=true 时可选、持久化
assistant.message.delta
assistant.message.completed     finish_reason=tool_call
tool.execution.started
tool.execution.completed
tool.result
assistant.message.started
assistant.message.delta
assistant.message.completed     finish_reason=stop
session.status.idle
stream.end
```

连接是请求范围的，不是永久长连接。Steer/FollowUp 被活动执行接收后，从原连接继续输出；客户端断线、超时或订阅溢出不等于 abort。执行快照为 `thinking=false` 时三类 thinking 事件全部省略。

### 持久化

DDL 使用 `t_` 前缀：`t_sessions`、`t_session_entries`、`t_session_sequences`、`t_session_materialized`、`t_session_tombstone`、`t_session_cleanup_task`。Entry 用 `parent_id` 保留分支结构，用 `entry_seq` 提供 Session 内严格持久化顺序；历史 API 只投影当前叶节点回溯得到的分支。`assistant.thinking.completed` 是第四类权威 Entry；关闭 thinking 只在查询中隐藏它，不删除记录。SQL 在分页 `LIMIT` 前应用过滤，保证每页数量和 next page 位置一致。

事件 page 使用 AES-GCM 保护并绑定 Session、`afterSeq`、签发时 thinking 和有效期。Session thinking 切换后，旧 page 以 `INVALID_EVENT_LIST_QUERY` 失败，调用方必须从第一页重新读取。

### 容量

- 全局最多 100 个活动执行；
- 单个执行默认最多 30 分钟；
- 单个 SSE 订阅最多缓存 256 个事件或 1 MiB；
- Steer/FollowUp 队列最多 32 条或 1 MiB，超限返回 429；
- 心跳间隔 15 秒。

## 6. 验证证据

以下验证针对 3.0.0 候选实现执行：

- 主仓 `./mvnw clean test`：2767 个测试通过，0 失败、0 错误；
- `mate-campusclaw` `clean test`：2767 个测试通过，0 失败、0 错误；
- `RuntimeSessionRepositoryOpenGaussIT`：连接真实 `opengauss/opengauss-server:latest`，14 个测试通过；
- `RuntimeHttpProcessOpenGaussIT`：1 个跨进程测试通过；启动打包后的真实 JVM 进程并连接真实 openGauss，覆盖创建、读取、SSE、409 删除、abort、204 删除、404 读取和墓碑；
- `./mvnw -pl modules/coding-agent-cli -am package -DskipTests`：可执行 JAR 打包通过；
- `./scripts/sync-mate-campusclaw.sh`：镜像同步和编译验证通过；
- `./mvnw spotless:apply`：格式化检查通过；
- Checkstyle 为 0 个违规；
- 编译器语法树审计确认本次修改的 Java 方法均不超过 50 个非空物理行；
- `git diff --check` 通过。

国际化实现另外覆盖：无基础资源包的消息源上下文启动、双 Locale key 与
`RuntimeErrorCode` 完全一致、`Accept-Language` 权重与英文回退、HTTP 中文错误、SSE 中文
`stream.error`，以及 `mate-campusclaw` 资源目录迁移和旧基础包删除。

发布前已重新执行全量测试、镜像同步、PlantUML、文档链接和 Git 校验；最终结果同时记录在发布提交报告中。

## 7. 文档策略

字段级 API 契约以独立设计仓库中的 `chat-http-v1-review.html` 为唯一评审页面；本仓只维护实现映射和运行说明。旧 `docs/openapi/campusclaw-api.yaml`、`docs/server-api.md`、WebFlux/ServerMode ADR 与公开 WebSocket 文档已删除。

## 8. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 3.0.0 | 2026-08-20 | 对齐 HTTP 1.37 契约：类型化资源 ID、创建默认 thinking、移除 Runtime 本地 Header 认证、删除消息体 type，并实现 thinking 事件持久化、查询过滤和游标绑定 |
| 2.2.0 | 2026-08-20 | 落地显式双 Locale 消息源、标准语言协商、HTTP/SSE 错误国际化和镜像资源迁移 |
| 2.1.0 | 2026-08-19 | 基于 `f899547d` 整改目录边界、事件职责、错误目录、异步日志和多实例执行归属错误 |
| 2.0.0 | 2026-08-18 | 以实现提交 `8691e880` 重写，修正 MVC、鉴权边界、删除语义、Agent 目录与 `file_ids` 行为 |
| 1.x | 2026-08-18 | 逐接口开发日志，包含已经失效的 WebFlux、模板快照和文件解析方案 |
