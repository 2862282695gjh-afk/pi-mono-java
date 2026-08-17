# CampusClaw Runtime HTTP+SSE V1 实施计划

- 计划版本：1.6.0
- 更新日期：2026-08-18
- 状态：已完成
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
- `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/`
- `modules/coding-agent-cli/src/main/resources/mapper/session/RuntimeSessionMapper.xml`

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
| 流式协议 | 源码基线的 `/api/chat` 使用旧 SSE 事件，断线触发 abort，并公开 WebSocket 路径 | 新路径使用请求范围 HTTP+SSE，断线不 abort，终止事件后断开；旧公开 WebSocket 实现已删除 | 这是已确认的恢复与控制语义；属于架构变更 |
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

旧 `/api/*` 本地开发 HTTP 接口只在不影响新契约的前提下保留。公开 WebSocket Runtime
处理器、路由、测试页面、AsyncAPI 和对应设计说明已经清理；AI Provider 层的
`Transport.WEBSOCKET` 是模型供应商传输能力，不是 CampusClaw Runtime 公共接口，予以保留。

## 接口实施进度

| 序号 | 方法与路径 | 主要验收点 | 状态 |
|---:|---|---|---|
| 1 | `POST /campusclaw-service/v1/agents/{agent_id}/sessions` | 生成 ID、默认模型、idle、thinking=false、201/Location | 已实现并验证 |
| 2 | `GET /campusclaw-service/v1/sessions/{session_id}` | 当前资源、强 ETag、no-store | 已实现并验证 |
| 3 | `DELETE /campusclaw-service/v1/sessions/{session_id}` | 幂等 204、异步清理、仅两字段 tombstone | 已实现并验证 |
| 4 | `POST /campusclaw-service/v1/sessions/{session_id}/events` | user.message、完整 SSE 序列、断线不 abort | 已实现并验证 |
| 5 | `GET /campusclaw-service/v1/sessions/{session_id}/events` | 当前分支、三种持久化事件、不透明游标 | 已实现并验证 |
| 6 | `GET /campusclaw-service/v1/sessions/{session_id}/models` | `current_model_id` 与 `models` 字符串数组 | 已实现并验证 |
| 7 | `PUT /campusclaw-service/v1/sessions/{session_id}/model` | idle、强 If-Match、无操作不增版本 | 已实现并验证 |
| 8 | `PUT /campusclaw-service/v1/sessions/{session_id}/thinking` | 布尔深度思考、idle、强 If-Match | 已实现并验证 |
| 9 | `POST /campusclaw-service/v1/sessions/{session_id}/steers` | running、文本、优先队列、202 | 已实现并验证 |
| 10 | `POST /campusclaw-service/v1/sessions/{session_id}/follow-ups` | running、文本、FIFO、202 | 已实现并验证 |
| 11 | `POST /campusclaw-service/v1/sessions/{session_id}/abort` | idle 幂等、运行中级联取消并清队列、204 | 已实现并验证 |

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

## 第一批接口验证证据

接口 1—3 已完成公共 ResultBean、双凭据鉴权、Agent 授权 port、中英文错误、
Session 所有权、MyBatis 持久化、强 ETag、tombstone、cleanup task 和可重试异步清理
worker 的纵向实现。
独立开发代码使用 `ResultBeanFactory.getFactory().normal(...)` 包装普通成功响应；公司
ResultBean 和鉴权制品坐标仍未知，未臆造内部依赖，授权与凭据校验均保留可替换 Bean。

已真实执行以下验证：

- `RuntimeSessionRoutesTest`、`RuntimeSessionServiceTest` 与 `SessionCleanupWorkerTest`：
  16 个测试，0 失败、0 错误、
  0 跳过；覆盖 JWT、APPKEY、混用拒绝、国际化、ResultBean 字段、ISO-8601 时间、
  Agent 创建权限、所有权、Retry-After、ETag 和幂等删除。
- 官方 `opengauss/opengauss-server:latest` 容器实际运行
  `openGauss 7.0.0-RC3 build 01b7e318`；完整安装 DDL 成功执行。
- `RuntimeSessionRepositoryOpenGaussIT`：7 个测试，0 失败、0 错误、0 跳过；覆盖
  Session/sequence/materialized 三表原子创建、查询映射、两字段 tombstone、cleanup
  task 抢占/延期重试/物理清理、已删除 ID 永不复用、创建失败回滚和删除失败回滚。
- 从源码基线提取 V1 建库 DDL，插入一条 legacy Session 后执行
  `V1_0_0_to_V2_0_0__schema.sql` 与 verify；旧记录保留，`updated_at=created_at`，
  新增字段回填值和约束均通过。
- 以可执行 Spring Boot JAR 启动真实 Reactor Netty 进程并连接上述 openGauss：依次得到
  `POST 201`、`GET 200`、跨调用方 `403`、`DELETE 204`、删除后 `GET 404`；查询数据库
  确认 tombstone 存在且 cleanup task 为 `PENDING`。最终构建再次创建并删除 Session，
  等待实际调度周期后确认 cleanup task 为 0、历史/序号/物化残留为 0、tombstone 仍为 1。

进程测试先后发现并修复了三个仅靠 mock 不易暴露的问题：openGauss Maven JDBC 制品
在可执行 JAR 中嵌入第二套 SLF4J API、lazy initialization 导致 Engine 初始化器未执行，
以及 OffsetDateTime 默认被编码为数字。Runtime 现使用 openGauss 兼容的标准 pgjdbc，
Engine 改为构造器依赖，边界时间显式编码为 ISO-8601 字符串。

## 第二批接口验证证据

接口 4—5 已完成 `user.message` 事务接受、Session 唯一 active execution、
pi `AgentEvent` 到公共 SSE 的投影、三种公共 Entry 持久化、当前分支递归查询和
AES-GCM 不透明分页游标。原始 thinking 块不对外暴露；客户端取消 SSE 订阅不会
abort 已接受的执行。`RuntimeFileResolver` 是可替换的公司文件服务集成端口；
由于内部制品和协议未提供，独立适配器对非空 `file_ids` 安全失败，未臆造调用。

已真实执行以下验证：

- `RuntimeEventRoutesTest`、`RuntimeEventServiceTest`、`RuntimeEventProjectorTest` 和
  `RuntimeEventCursorCodecTest`：18 个测试，0 失败、0 错误、0 跳过；覆盖精确 HTTP/SSE
  形状、未知字段和非法 JSON 拒绝、真实 pi Agent 工具事件序列、断线不 abort、
  启动前同步失败、流内错误、Session 绑定/篡改/过期游标和分页响应。
- `RuntimeSessionRepositoryOpenGaussIT`：在官方 openGauss 7.0.0-RC3 容器中
  11 个测试，0 失败、0 错误、0 跳过；新增覆盖 Entry 严格序号、当前分支
  排除废弃分支、两个并发用户事件只有一个成功占用 Session，以及 idle
  Session 追加失败时的整体事务回滚。
- 不使用 mock Controller 的 `RuntimeHttpProcessOpenGaussIT`：启动实际 85 MB Spring Boot
  可执行 JAR、真实 Reactor Netty 端口、官方 openGauss 容器与本地 OpenAI SSE 协议桩；
  从 HTTP 创建 Session，提交事件并收到
  `user.message → assistant.message.started → assistant.message.delta →`
  `assistant.message.completed → session.status.idle → stream.end`，然后用不透明游标
  分两页读回持久化事件，并直接查询数据库确认 `state=idle`、`resource_version=3`
  与两条父子 Entry。
- 完整 `./mvnw -B verify` 执行 1315 个 coding-agent 测试及所有上游
  模块测试，0 失败、0 错误、0 跳过，并产出可执行 JAR。
- 同步生成的 `mate-campusclaw` 执行 `mvn -B verify`，共 2785 个测试，
  0 失败、0 错误、0 跳过；镜像可执行 JAR 也通过同一进程级 HTTP+SSE
  与真实 openGauss 集成测试。

本批还在质量门禁中发现并修复了多个实现问题：`RuntimeAuthFilter`
为写入认证上下文重建 `ServerRequest` 时丢失 POST body，以及加密游标测试将随机密文
“偶然包含数字 19”误判为明文泄漏。进一步审计还修复了 Agent 同步启动失败
可能遗留 `running` 状态、Mapper 更新行数未校验、底层查询错误未映射到契约错误码，
以及恢复 Assistant Message 时 `Usage` 为空的问题。

## 第三批接口验证证据

接口 6—8 已完成调用身份感知的实时模型列表、稳定 `enabledModels` 顺序、模型切换、
布尔深度思考开关、强 `If-Match`、资源版本 CAS、同值无操作以及模型能力归一。
模型或 thinking 变更与用户事件恢复共用 Session 条带操作锁；数据库事务继续使用
`SELECT ... FOR UPDATE`，因此内存 Agent 与持久化配置不会和新一轮执行交叉生效。

已真实执行以下验证：

- `RuntimeSessionConfigurationRoutesTest`、`RuntimeSessionConfigurationServiceTest`、
  `CatalogRuntimeModelManagerTest` 及相关事件竞态测试：40 个测试，0 失败、0 错误、
  0 跳过；覆盖字符串模型数组、JWT/APPKEY、严格 JSON 类型与未知字段、428/412、
  422 能力错误、503 `Retry-After`、同值无操作、能力归一和操作锁顺序。
- `RuntimeSessionRepositoryOpenGaussIT`：在官方 openGauss 7.0.0-RC3 中执行 14 个测试，
  0 失败、0 错误、0 跳过；新增覆盖模型切换与 thinking 原子更新、无操作不更新时间和
  版本，以及两个并发配置更新只有一个成功、另一个返回版本不匹配。
- 扩展 `RuntimeHttpProcessOpenGaussIT` 后，以主仓可执行 JAR 和镜像可执行 JAR 分别启动
  真实 Reactor Netty 进程；完成 GET 模型列表、开启深度思考、切换至不支持推理的模型
  并自动归一 `thinking=false`、同模型无操作保持 ETag、旧 ETag 返回 412，且直接查询
  openGauss 确认最终 `resource_version=5`。两个进程测试均为 1 个测试，0 失败、0 错误、
  0 跳过。
- 主仓完整 `./mvnw -B verify` 执行 1335 个 coding-agent 测试及所有上游模块测试，
  0 失败、0 错误、0 跳过，并产出可执行 JAR。
- 同步生成的 `mate-campusclaw` 首次 `mvn -B verify` 在无关的 TUI 自动补全竞态测试中
  偶发失败 1 次；该测试单独重跑通过，随后完整重跑 2805 个测试全部通过，0 失败、
  0 错误、0 跳过。未把首次失败隐藏或计为成功。

本批的并发审计发现仅在已存在 `RuntimeSessionHolder` 上加锁会让“冷 Session 恢复执行”
与配置变更产生窗口，因此把操作锁提升到 `RuntimeSessionEngineRegistry` 的固定条带锁；
即使 Session 尚未恢复到内存，也能按 `session_id` 串行化恢复、配置和 Agent 同步。

## 第四批接口验证证据

接口 9—11 已完成 Session 级 Steer、FollowUp 与 Abort。两个消息接口只把文本放入
当前 active execution 的进程内队列，返回 202 时不创建公共资源也不提前写 Entry；
Agent 实际投递时才复用既有 `user.message` 持久化与 SSE。两个队列固定
`ONE_AT_A_TIME`，Steer 在无工具自然结束和工具阶段之后都优先于 FollowUp。
Abort 关闭消息接受、清空未投递队列、取消当前 Agent，并等待持久化状态和 SSE
共同收敛到 idle 后返回 204。

已真实执行以下验证：

- `AgentLoopTest`、`RuntimeSessionControlRoutesTest`、
  `RuntimeSessionControlServiceTest` 与 `RuntimeEventServiceTest`：24 个测试，
  0 失败、0 错误、0 跳过；覆盖无工具 Turn 的 Steer 优先级、逐条投递、严格请求体、
  JWT/APPKEY、端点专属错误码、202 ResultBean、idle 409、Abort 204、自然结束与消息
  接受竞态，以及自然结束与 Abort 竞态。
- 扩展后的 `RuntimeHttpProcessOpenGaussIT` 启动最终可执行 JAR、真实 Reactor Netty、
  本地模型 SSE 协议桩和官方 openGauss 7.0.0-RC3。在首个模型响应保持打开时，另行发出
  Steer 与 FollowUp HTTP 请求，验证同一 POST `/events` 流中三组
  `user.message → assistant.message` 严格按 Steer、FollowUp 顺序输出，随后逐页读取
  六条持久化 Entry；另一个 Session 在模型请求未完成时 Abort，原 SSE 以
  `session.status.idle` 和 `stream.end(reason=aborted)` 收尾，重复 Abort 仍返回 204。
  该进程测试 1 个测试，0 失败、0 错误、0 跳过。
- 主仓完整 `./mvnw -B verify` 执行 1352 个 coding-agent 测试、271 个 Agent Core
  测试及全部其他上游模块测试，0 失败、0 错误、0 跳过，并重新产出可执行 JAR。

进程测试首次扩展后仍沿用“历史只有两条”的旧分页断言，因此在第二页仍有
`next_page` 时失败；把断言改为逐页遍历六条 Entry 后，完整真实进程测试通过。
并发审计同时发现消息可能在 Agent 自然结束与完成回调之间被接受，现由 Session
条带操作锁和 `RuntimeActiveExecution.acceptingControls` 共同线性化：消息先入队就继续
同一 execution，完成先发生则关闭接受并返回 `SESSION_NOT_RUNNING`，不会产生 202 后丢消息。

## HTTP+SSE 收口与最终验证证据

完成 11 个接口后，进一步删除旧公开 WebSocket Handler、路由、单元测试、AsyncAPI、
测试客户端和 WebSocket 专项设计文档；README、Server API、现存旧 `/api/*` OpenAPI
及模块设计均统一说明 CampusClaw Runtime 使用 HTTP+SSE。Runtime 路径增加 CORS
预检进程级断言。删除 Session 的审计还发现旧实现会调用全局 `SubAgentRegistry.cancelAll`，
可能误停其他 Session；现只依赖当前 Agent 的 `CancellationToken` 向其子 Agent 传播取消。

全部 Mermaid 设计图已迁移为五个主题下 15 个具名 PlantUML 图，并生成、提交对应 SVG；
每个 `.puml` 都记录源码基线、相对路径和设计理由，内容仅含 ASCII。最终真实验证如下：

- 主仓 `./mvnw -B verify`：coding-agent 1335 个测试及全部上游模块测试通过，0 失败、
  0 错误、0 跳过；测试数减少 17 是删除 10 个 WebSocket Handler 测试和 7 个只服务
  WebSocket query 参数的测试。
- `mate-campusclaw` 全量 `verify`：2806 个测试通过，0 失败、0 错误、0 跳过。
- 主仓与 mate 可执行 Spring Boot JAR 分别连接官方 openGauss 7.0.0-RC3、启动真实
  Reactor Netty，并执行完整 HTTP+SSE 进程测试，各 1 个测试，均为 0 失败、0 错误、
  0 跳过；覆盖 11 个接口、SSE、CORS 预检、鉴权、ETag、控制队列和 Abort。
- 主仓与 mate 的 `RuntimeSessionRepositoryOpenGaussIT` 分别执行 14 个测试，均为
  0 失败、0 错误、0 跳过；使用真实数据库事务、锁、约束、分页与清理流程。
- `plantuml -tsvg diagram.puml`、SVG XML 校验、Markdown 图路径与 PlantUML 行锚校验、
  `.puml` ASCII 校验、无 Mermaid 扫描、OpenAPI YAML 解析、`spotless:check` 和
  `git diff --check` 均纳入最终质量门禁。

## 推进日志

- 2026-08-18：从最新 `origin/main` 创建独立工作树和
  `codex/campusclaw-http-v1` 分支；原脏工作区保持不变。
- 2026-08-18：完整解析主 HTML 中 11 个 `data-review-model` 契约，确认版本
  `1.29.0`、路径、请求、响应和错误集合。
- 2026-08-18：读取当前 Runtime、Session、Agent、队列、数据库与启动 wiring，
  确认函数式 WebFlux、现有队列能力及数据库缺口。
- 2026-08-18：实施前 Spotless 与全量 Maven Verify 均通过。
- 2026-08-18：完成接口 1—3、公共鉴权/ResultBean/错误国际化和 Session 数据库基础；
  单元契约测试、真实 openGauss Mapper/事务测试、V1 数据迁移测试和进程级 HTTP 测试通过。
- 2026-08-18：同步接口 1—3 到 `mate-campusclaw`，补齐 MyBatis mapper 资源和标准
  pgjdbc；镜像模块 `mvn -B verify` 共执行 2767 个测试，0 失败、0 错误、0 跳过，
  镜像可执行 JAR 连接同一真实 openGauss 后创建 Session 返回 201。
- 2026-08-18：完成接口 4—5 的事件事务、Agent 投影、SSE、当前分支分页与不透明
  游标；单元/路由、完整模块回归、真实 openGauss 并发与打包 JAR 跨进程 HTTP+SSE
  测试通过。
- 2026-08-18：完成接口 6—8 的模型列表、模型切换和深度思考配置；强 ETag/CAS、
  同值无操作、模型能力归一、Registry 条带锁、真实 openGauss 并发、主仓与镜像
  全量验证通过。
- 2026-08-18：完成接口 9—11 的 Steer、FollowUp 和 Abort；消息接受/自然结束竞态、
  Abort/自然结束竞态、真实并发 HTTP+SSE 与 openGauss 持久化顺序均已验证。
- 2026-08-18：清理公开 WebSocket Runtime 与相关文档，将 15 个 Mermaid 图迁移为
  PlantUML/SVG；主仓、mate 全量回归、真实可执行 JAR 进程测试和真实 openGauss
  仓储集成测试全部通过，11 个接口实施完成。
