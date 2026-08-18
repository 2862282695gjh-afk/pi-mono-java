# CampusClaw HTTP V1 实施记录

> 版本：2.0.0
>
> 状态：已实现并完成发布前验证
>
> 源码基线：`cb12ac7ce5637935c7e55f341b834afc71978d11`
>
> 实现提交：`8691e8800f05f28afe22499050c29220ef5b7475`
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
| 鉴权形状 | `runtimeapi/auth/RuntimeRequestAuthenticator.java`、`web/RuntimeAuthenticationInterceptor.java` |
| ResultBean / i18n | `runtimeapi/result/*`、`messages.properties`、`messages_zh_CN.properties` |
| Session 业务 | `runtimeapi/session/RuntimeSessionService.java`、`RuntimeSessionConfigurationService.java`、`RuntimeSessionControlService.java` |
| 事件与 SSE | `runtimeapi/event/RuntimeEventService.java`、`RuntimeEventStream.java`、`RuntimeSseDispatcher.java` |
| 执行生命周期 | `runtimeapi/runtime/RuntimeSessionEngineRegistry.java`、`RuntimeActiveExecution.java`、`RuntimeExecutionTimeoutScheduler.java` |
| 持久化 | `runtimeapi/persistence/MyBatisRuntimeSessionRepository.java`、`mapper/session/RuntimeSessionMapper.xml` |
| DDL | `src/main/resources/db/gaussdb/install/session_schema.sql` |
| Agent 目录 | `runtimeapi/agent/FileAgentDirectoryResolver.java`、`RuntimeAgentPromptLoader.java` |
| 公司镜像 | `mate-campusclaw/`、`scripts/sync-mate-campusclaw.sh` |

## 3. 接口完成情况

| 序号 | 方法与路径 | 核心语义 | 结果 |
|---:|---|---|---|
| 1 | `POST /campusclaw-service/v1/agents/{agent_id}/sessions` | 服务端生成 Session，创建时不要求 model | 已实现 |
| 2 | `GET /campusclaw-service/v1/sessions/{session_id}` | 返回名称、state、model、thinking、版本等精简状态 | 已实现 |
| 3 | `DELETE /campusclaw-service/v1/sessions/{session_id}` | running 返回 409；idle 幂等删除；墓碑仅两字段 | 已实现 |
| 4 | `POST /campusclaw-service/v1/sessions/{session_id}/events` | 接收 `user.message`，直接返回完整 SSE 生命周期 | 已实现 |
| 5 | `GET /campusclaw-service/v1/sessions/{session_id}/events` | 当前分支对话 Entry，不透明服务端游标 | 已实现 |
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
| 鉴权 | 基线无 Runtime 鉴权 | 校验 JWT/APPKEY Header 组合形状 | 产品约束：真实性和授权由上游 mate-service 保证 |
| Agent 来源 | 曾设计独立模板快照 | 直接读取 Agent 目录，只启用 read 工具 | 架构变更：去掉重复模板仓库和缓存 |
| 文件 | 曾设计 Runtime 文件解析 port | `file_ids` 原样组成固定提示块 | 产品约束：文件内容不由 Runtime 下载 |

## 5. 关键运行语义

### SSE

典型流式序列为：

```text
user.message
assistant.message.started
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

连接是请求范围的，不是永久长连接。Steer/FollowUp 被活动执行接收后，从原连接继续输出；客户端断线、超时或订阅溢出不等于 abort。

### 持久化

DDL 使用 `t_` 前缀：`t_sessions`、`t_session_entries`、`t_session_sequences`、`t_session_materialized`、`t_session_tombstone`、`t_session_cleanup_task`。Entry 用 `parent_id` 保留分支结构，用 `entry_seq` 提供 Session 内严格持久化顺序；历史 API 只投影当前叶节点回溯得到的分支。

### 容量

- 全局最多 100 个活动执行；
- 单个执行默认最多 30 分钟；
- 单个 SSE 订阅最多缓存 256 个事件或 1 MiB；
- Steer/FollowUp 队列最多 32 条或 1 MiB，超限返回 429；
- 心跳间隔 15 秒。

## 6. 验证证据

以下验证针对实现提交执行：

- 主仓 `./mvnw test`：1287 个测试通过，0 失败、0 错误；
- `mate-campusclaw` 全量测试：与主仓同步后的镜像全部通过；
- `RuntimeSessionRepositoryOpenGaussIT`：连接真实 `opengauss/opengauss-server:latest`，14 个测试通过；
- `RuntimeHttpProcessOpenGaussIT`：启动打包后的真实 JVM 进程，连接真实 openGauss，覆盖创建、读取、SSE、409 删除、abort、204 删除、404 读取和墓碑；
- `java -jar ... cli --version`：打包 JAR 真实 CLI 路径退出码 0；
- 前端 `npm run build` 通过，`npm audit --audit-level=high` 为 0 个高危漏洞；
- Checkstyle 为 0 个违规；
- 编译器语法树审计确认本次修改的 Java 方法均不超过 50 个非空物理行；
- `git diff --check` 通过。

最终发布前仍会重新执行全量测试、镜像同步、PlantUML、文档链接和 Git 校验；最终结果以发布提交报告为准。

## 7. 文档策略

字段级 API 契约以独立设计仓库中的 `chat-http-v1-review.html` 为唯一评审页面；本仓只维护实现映射和运行说明。旧 `docs/openapi/campusclaw-api.yaml`、`docs/server-api.md`、WebFlux/ServerMode ADR 与公开 WebSocket 文档已删除。

## 8. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 2.0.0 | 2026-08-18 | 以实现提交 `8691e880` 重写，修正 MVC、鉴权边界、删除语义、Agent 目录与 `file_ids` 行为 |
| 1.x | 2026-08-18 | 逐接口开发日志，包含已经失效的 WebFlux、模板快照和文件解析方案 |
