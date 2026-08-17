# CampusClaw Runtime HTTP+SSE V1 实施计划

- 计划版本：1.0.0
- 更新日期：2026-08-18
- 状态：实施中
- 实际源码基线：`889dcb1b7dd5f47addd3b372ef31392c9044ca24`
- 设计仓库基线：`ca03bc3898f4e0605ebf71e38367e77acc3f9391`
- 实施分支：`codex/campusclaw-http-v1`

## 权威契约与源码证据

权威接口契约为设计仓库的
`pi-mono-java-manager-driven-multi-agent-runtime/chat-http-v1-review.html`，版本为
`1.29.0`。配套语义来自下列设计文件：

- `pi-mono-java-manager-driven-multi-agent-runtime/chat-http-v1-design.md`
- `pi-mono-java-manager-driven-multi-agent-runtime/README.md`
- `virtual-thread-concurrency-management/README.md`
- `agent-runtime-template/README.md`
- `campusmate-attachment-service/README.md`

实现分析以本仓库下列源码为准：

- `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/mode/server/ServerMode.java`
- `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/mode/server/SessionPool.java`
- `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/session/AgentSession.java`
- `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/session/SessionManager.java`
- `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/session/SessionTree.java`
- `modules/agent-core/src/main/java/com/campusclaw/agent/Agent.java`
- `modules/agent-core/src/main/java/com/campusclaw/agent/loop/AgentLoop.java`
- `modules/agent-core/src/main/java/com/campusclaw/agent/queue/MessageQueue.java`
- `modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql`
- `modules/coding-agent-cli/src/main/resources/application.yml`

设计文档记录的旧源码基线是
`195746614096312d4e93afb029d387837db78e0b`。本次实施使用的实际源码基线更新为
`889dcb1b7dd5f47addd3b372ef31392c9044ca24`；因此每项实现均以当前源码重新核对，
不把旧行号或旧行为当作当前事实。

## 已观察行为、目标决策与理由

| 分类 | 当前源码事实 | 目标决策 | 理由 |
|---|---|---|---|
| HTTP 运行方式 | `spring.main.web-application-type=none`，`ServerMode` 手工启动 Reactor Netty 并组合 `RouterFunction` | 使用函数式 Controller/路由，显式挂到现有服务器 | 这是当前启动模型；`@RestController` 不会自动形成服务端点 |
| Session | `/api/chat` 使用 `SessionPool` 和调用方 `conversation_id`，本地 JSONL 持久化 | 新增服务端生成 `session_id` 的 Runtime Session 聚合 | 契约要求 Agent 绑定、版本、状态、模型和并发控制 |
| 执行标识 | Agent 以一次 `prompt` 执行一轮，但无公开 Run 资源 | 仅保留内部 active execution，不公开 `run_id` | 已确认契约以 Session Events 表达执行 |
| 流式协议 | `/api/chat` 使用旧 SSE 事件，断线触发 abort；另有 `/api/ws/chat` | 新路径使用请求范围 HTTP+SSE，断线不 abort，终止事件后断开 | 这是已确认的恢复与控制语义；属于架构变更 |
| 队列控制 | `Agent` 已有 steer、follow-up 队列和 abort | 在 Session 级接口上复用并补充持久化、状态与错误语义 | 避免重复执行引擎并保持 pi 行为 |
| 历史 | `SessionTree` 通过 `parentId` 重建当前分支 | 数据库按当前分支、`entry_seq` 升序分页投影三种持久化事件 | 保留 pi 分支语义，同时满足 HTTP 恢复 |
| 数据库 | 已有 `t_sessions`、`t_session_entries`、序列表和物化表，无 Mapper | 演进表结构并新增 DTO、MyBatis Mapper 和事务 Service | 目标接口必须跨进程持久化且通过真实 openGauss 验证 |
| 鉴权 | Runtime 新路径尚无鉴权 | JWT 为默认，APPKEY 兼容；两套凭据互斥且不降级 | 公司安全约束 |
| 响应 | 旧接口使用临时 Map | 普通成功使用 `resCode/resMsg/result`，异常仅 `resCode/resMsg`；204/SSE 不包装 | 公司 ResultBean 契约 |
| 公司依赖 | 内部 ResultBeanFactory 和鉴权制品坐标未知 | 提供契约等价的默认适配器与可替换 port/profile | 不臆造内部 Maven 坐标或内部 API |
| Agent 模板 | 当前源码没有已发布 Agent 快照解析实现 | 定义可替换的 Agent 发布快照解析 port，并提供可测试的默认实现 | 该部分属于目标设计，不伪称现有 pi 行为 |
| 附件 | 当前源码没有已落地的 Attachment Service 客户端 | 定义 `file_id` 校验/解析 port；无文件请求可独立运行，测试注入替身 | 依赖外部服务的目标设计与本地 Runtime 解耦 |

## 实施边界

本次实施 `modules/*`，并使用仓库同步脚本生成 `mate-campusclaw` 镜像。新 Runtime
接口不引入公开 Run 资源、不接受 `Idempotency-Key`、不接受 `traceparent`，不在请求中
覆盖 model/thinking。公司私服坐标、JWT 密钥、APPKEY 密钥和生产数据库凭据不进入仓库。

旧 `/api/*` 本地开发接口只在不影响新契约的前提下保留；公开 WebSocket Runtime 语义、
对应 AsyncAPI 和面向 WebSocket 的设计说明将在新 HTTP+SSE 路径具备等价能力并通过回归后清理。

## 接口实施进度

| 序号 | 方法与路径 | 主要验收点 | 状态 |
|---:|---|---|---|
| 1 | `POST /campusclaw-service/v1/agents/{agent_id}/sessions` | 生成 ID、默认模型、idle、thinking=false、201/Location | 待实施 |
| 2 | `GET /campusclaw-service/v1/sessions/{session_id}` | 当前资源、强 ETag、no-store | 待实施 |
| 3 | `DELETE /campusclaw-service/v1/sessions/{session_id}` | 幂等 204、异步清理、仅两字段 tombstone | 待实施 |
| 4 | `POST /campusclaw-service/v1/sessions/{session_id}/events` | user.message、完整 SSE 序列、断线不 abort | 待实施 |
| 5 | `GET /campusclaw-service/v1/sessions/{session_id}/events` | 当前分支、三种持久化事件、不透明游标 | 待实施 |
| 6 | `GET /campusclaw-service/v1/sessions/{session_id}/models` | `current_model_id` 与 `models` 字符串数组 | 待实施 |
| 7 | `PUT /campusclaw-service/v1/sessions/{session_id}/model` | idle、强 If-Match、无操作不增版本 | 待实施 |
| 8 | `PUT /campusclaw-service/v1/sessions/{session_id}/thinking` | 布尔深度思考、idle、强 If-Match | 待实施 |
| 9 | `POST /campusclaw-service/v1/sessions/{session_id}/steers` | running、文本、优先队列、202 | 待实施 |
| 10 | `POST /campusclaw-service/v1/sessions/{session_id}/follow-ups` | running、文本、FIFO、202 | 待实施 |
| 11 | `POST /campusclaw-service/v1/sessions/{session_id}/abort` | idle 幂等、运行中级联取消并清队列、204 | 待实施 |

## 验证矩阵

| 层级 | 必须真实执行的验证 |
|---|---|
| 单元 | VO 约束、错误国际化、游标签名/绑定、ETag、状态机、事件投影 |
| 鉴权 | JWT、APPKEY、缺失、不完整、混用、校验失败、无权限矩阵 |
| 数据库 | openGauss 容器建表、事务追加、严格序号、当前分支、删除/tombstone、并发 CAS |
| HTTP | 独立 JVM/进程启动后的 11 条真实 HTTP 路径、Header 与 JSON 精确契约 |
| SSE | 事件顺序、工具调用、流内错误、abort、客户端断线后 GET 恢复 |
| 并发 | 同 Session 唯一 active execution、并发 PUT/POST、删除与执行竞争 |
| 回归 | `./mvnw -B spotless:check`、`./mvnw -B verify` |
| 镜像 | `./scripts/sync-mate-campusclaw.sh` 及镜像编译/测试 |
| Git | `git diff --check`、显式暂存、阶段提交、推送当前分支 |

## 已执行基线

在任何实现修改前，已从实际源码基线真实执行：

- `./mvnw -B spotless:check`：退出码 0，耗时约 3.3 秒。
- `./mvnw -B verify`：退出码 0，耗时约 29.8 秒；无失败、错误或跳过。

这两个结果仅证明实施前基线健康，不替代实施后的全部质量门禁。

## 推进日志

- 2026-08-18：从最新 `origin/main` 创建独立工作树和
  `codex/campusclaw-http-v1` 分支；原脏工作区保持不变。
- 2026-08-18：完整解析主 HTML 中 11 个 `data-review-model` 契约，确认版本
  `1.29.0`、路径、请求、响应和错误集合。
- 2026-08-18：读取当前 Runtime、Session、Agent、队列、数据库与启动 wiring，
  确认函数式 WebFlux、现有队列能力及数据库缺口。
- 2026-08-18：实施前 Spotless 与全量 Maven Verify 均通过。
