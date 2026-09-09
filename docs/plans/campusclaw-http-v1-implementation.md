# CampusClaw HTTP V1 实施记录

> 版本：3.11.1
>
> 状态：已实现并按 Runtime-only 现状校准
>
> 变更前 Java 行为基线：`cb12ac7ce5637935c7e55f341b834afc71978d11`
>
> Runtime HTTP 1.38 审查实现基线：`304eda06ff603fc9b6bbcaad0c296cc151a7defb`
>
> Runtime-only 当前校准基线：`42eb8b0ccb98b512d886722f2ad7ce8340d5a77a`
>
> 工具执行凭据边界变更前基线：`320d790726a70aada6100052952d5494d2a378ac`
>
> HTTP 1.38 设计契约 `main` 合并基线：`superheromeZzh/pi-mono-java-design@ea4c70c33a458182b354ed0908cfc0ef54f13bc0`
>
> 国际化实现起点：`3a6358bc9dd5837cdf5ac866fc0761298372510a`
>
> 公司 GaussDB 脚本布局源码基线：`origin/main@d84dd3d6a306b7587c70b29e0100e741dacef989`
>
> 公司 GaussDB 脚本布局实现：`c1335026`
>
> 公司 GaussDB 初始化样式源码基线：`origin/main@18bf7026d75cf28a9652025eaa19800eb36c4c64`
>
> 首版数据库范围基线：`17f91e20`（清理提交 `45d7ece9`）
>
> 初始实现提交：`8691e8800f05f28afe22499050c29220ef5b7475`
>
> 初始日期：2026-08-21
>
> 更新日期：2026-09-08

> 公司镜像相关路径和标识按 2026-09-03 的当前仓库位置展示；历史提交 SHA 仍是对应行为证据。

## 1. 目标与边界

本文档记录 CampusClaw 从“CLI 默认启动、手工 ServerMode 提供旧接口”的模型收口，以及后续
Runtime-only 架构演进。当前形态为：

- 默认 `java -jar` 启动 Spring Boot MVC HTTP 服务；
- 不再提供 CLI、TUI、RPC 或其他模式分发入口；
- Runtime 对外统一为 HTTP + 请求范围 SSE；
- 按已确认契约实现 11 个既有 Session 接口，以及共享命令清单 GET 和执行 POST；
- openGauss 作为 Session/Entry 持久化源；
- `campusclaw` 由同步脚本生成并验证；
- 删除旧公开 WebSocket、ServerMode、WebFlux 路由和本仓 OpenAPI 副本。

公司内部 ResultBean 制品坐标和真实类全限定名未提供，因此没有臆造依赖。实现通过 `ResultBeanAdapter` 保留替换点；公司 Bean 在集成工程中调用真实 `ResultBeanFactory.getFactory().normal()` 即可获得相同 Controller 开发体验。

## 2. 源码证据

共享常量迁移复核基线为 `ee3fdb4893228045f06b9b1d1b3b3bb505812c73`：该版仍使用独立常量类；本次架构调整
将 ID、HTTP 路径及请求限制迁入新 common 模块的 ClawConstants，以下对应路径已更新至目标实现。
常量值、Jakarta 校验触发位置和 HTTP 契约保持不变，见[共享常量设计](../designs/shared-constants.md)。

| 领域 | 实现路径与符号 |
|---|---|
| 变更前启动与模式 | `cb12ac7ce5637935c7e55f341b834afc71978d11` 的 `modules/coding-agent-cli/src/main/resources/application.yml`、`mode/server/ServerMode.java` |
| 变更前 Session 默认值 | `cb12ac7ce5637935c7e55f341b834afc71978d11` 的 `RuntimeSessionService#newSession` |
| 变更前 Agent 模板 | `cb12ac7ce5637935c7e55f341b834afc71978d11` 的 `runtimeapi/template/FileAgentRuntimeSnapshotProvider#readRevision` |
| 启动 | `modules/coding-agent-cli/.../CampusClawApplication.java` |
| HTTP 边界 | `modules/coding-agent-cli/.../runtimeapi/web/*Controller.java` |
| 调用上下文 Header 边界 | `RuntimeRequestContext#mateCredentials`、`RuntimeEventController#submit`；POST Events 捕获 `X-HW-ID`、`X-HW-APPKEY`、`Authorization`、`access-token`，Runtime 不包含认证器、认证拦截器或认证错误码 |
| Mate 发现与执行 Header | `MateToolClient#listAgentTools`、`#listSkillTools` 不接收凭据；`HttpMateToolClient#invokeTool` 只让 execute 请求携带 POST Events 收到的四项非空值 |
| 类型化资源 ID | `modules/common/src/main/java/com/campusclaw/common/constant/ClawConstants.java`、`runtimeapi/web/*Controller` 的 Jakarta 路径参数约束、`RuntimeExceptionHandler#handleInvalidParameter`、`MateServiceClient`、`AgentRuntimeManager`、`HttpMateToolClient`、`RandomSessionIdGenerator` |
| ResultBean / i18n | `runtimeapi/result/*`、`RuntimeMessageSourceConfiguration`、`RuntimeRequestContext`、`src/main/resources/i18n/messages_{en_US,zh_CN}.properties` |
| Session 业务 | `runtimeapi/session/RuntimeSessionService.java`、`RuntimeSessionConfigurationService.java`、`RuntimeSessionControlService.java` |
| 事件接受 | `runtimeapi/event/RuntimeEventService.java`、`RuntimeExecutionContextFactory.java` |
| thinking、Compaction 与 Usage 投影 | `runtimeapi/event/RuntimeEventProjector#persistAssistant`、`#projectCompactionCompleted`，`RuntimeEntryCodec#thinkingEntry`、`#compactionEntry`、`#usageRecord` |
| 事件查询 | `runtimeapi/event/RuntimeEventQueryService.java`、`RuntimeEventCursorCodec.java`、`RuntimeSessionMapper.xml#listCurrentBranch` |
| 执行协调与 SSE | `RuntimeExecutionCoordinator.java`、`RuntimeEventStream.java`、`RuntimeSseDispatcher.java` |
| 执行生命周期 | `runtimeapi/runtime/RuntimeSessionEngineRegistry.java`、`RuntimeActiveExecution.java`、`RuntimeExecutionTimeoutScheduler.java` |
| 持久化 | `runtimeapi/persistence/MyBatisRuntimeSessionRepository#appendEntryWithUsage`、`mapper/session/RuntimeSessionMapper.xml` |
| DDL | 模块表 DDL：`modules/coding-agent-cli/src/main/resources/db/gaussdb/install/session_schema.sql`；公司头部模板：`scripts/templates/initdb_gaussdbv5-header.sql`；生成与校验：`scripts/sync-campusclaw.sh#stage_database_install_script`；公司交付文件：`campusclaw/scripts/install/initdb_gaussdbv5.sql` |
| Agent 受管目录与工作区 | `runtime/AgentRuntimeManager.java`、`runtime/PreparedAgentRuntime.java`、`runtimeapi/agent/FileAgentDirectoryResolver.java`、`session/AgentSessionFactory.java`、`tool/workspace/AgentWorkspaceBoundary.java` |
| Runtime 默认工具 | `tool/builtin/BuiltInToolProperties.java`、`tool/builtin/ToolAssembler.java` |
| 公司镜像 | `campusclaw/`、`scripts/sync-campusclaw.sh` |

## 3. 接口完成情况

| 序号 | 方法与路径 | 核心语义 | 结果 |
|---:|---|---|---|
| 1 | `POST /campusclaw-service/v1/agents/{agentId}/sessions` | 生成类型化 Session ID，创建时初始化 `thinking=true` | 已实现 |
| 2 | `GET /campusclaw-service/v1/sessions/{sessionId}` | 返回名称、state、model、thinking、版本等精简状态 | 已实现 |
| 3 | `DELETE /campusclaw-service/v1/sessions/{sessionId}` | running 返回 409；idle 幂等删除；墓碑仅两字段 | 已实现 |
| 4 | `POST /campusclaw-service/v1/sessions/{sessionId}/events` | 请求体只含 `message/fileIds`；读取四项工具执行凭据并只在当次执行内存中持有；按 thinking 状态投影并直接返回 SSE | 已实现 |
| 5 | `GET /campusclaw-service/v1/sessions/{sessionId}/events` | 按当前 thinking 过滤持久化事件，page 绑定该状态 | 已实现 |
| 6 | `GET /campusclaw-service/v1/sessions/{sessionId}/models` | `currentModelId` + 模型 ID 字符串数组 | 已实现 |
| 7 | `PUT /campusclaw-service/v1/sessions/{sessionId}/model` | idle + 强 `If-Match`，同值不增版本 | 已实现 |
| 8 | `PUT /campusclaw-service/v1/sessions/{sessionId}/thinking` | 布尔深度思考 + 强 `If-Match` | 已实现 |
| 9 | `POST /campusclaw-service/v1/sessions/{sessionId}/steers` | running 时加入高优先级队列 | 已实现 |
| 10 | `POST /campusclaw-service/v1/sessions/{sessionId}/follow-ups` | running 时加入 FIFO 队列 | 已实现 |
| 11 | `POST /campusclaw-service/v1/sessions/{sessionId}/abort` | 幂等中止，清空未投递队列 | 已实现 |
| 12 | `GET /campusclaw-service/v1/sessions/{sessionId}/commands` | 一次完整缓存的 Builtin/Skill 轻量清单；按状态过滤；缺缓存为 503 与 Retry-After 3 | 已实现 |
| 13 | `POST /campusclaw-service/v1/sessions/{sessionId}/command` | 七Builtin业务JSON与Skill普通消息SSE；VO类型分流，执行前失败为JSON，断线不取消已接受执行 | 本片接入；跨进程验收另行交付 |

操作 12 的契约基线为只读设计仓 `88f4df16bc24bbfcd28e1ec374feb2de0db8be3b`，
实现提交为 `afee9bd333d0fc74ba0cef8b27b6df7357acdf30`，不沿用上方历史 HTTP 1.38 对该新增操作作证。
入口 `runtimeapi/web/RuntimeCommandCatalogController.java#list` 只包装应用层 VO；
`CommandCatalogService#list` 复用同一完整 Catalog，详细路径及验证边界见
[共享清单 HTTP 实现](../designs/command-catalog-http/README.md)。按当前明确交付授权，GET 独立于 POST 发布。

#245 合并基线 `146a6c9ecdc6eda987fcecd65b1e14416ce78bb1` 之后，
`1bf1ce6d631a5c394153bd8e8cce55a861d3a387` 删除未注册旧命令原型及其专属测试。
该清理切片未修改现有 Runtime 命令、公共压缩与上述 HTTP 路由，当时尚未发布POST Command。
删除清单与本轮验证见[命令原型清理](../designs/legacy-command-cleanup/README.md)。

操作13接入基线为 `2f52e9b80c95c5c27141362976b918227b1c5d60`，
契约依据为只读设计88f4df16及用户2026-09-08确认的Skill共享请求与正文同源公开方案。
新增 `runtimeapi/web/RuntimeCommandController.java#execute`；应用层复用
`CommandExecutionService#executeBuiltin` 与 `SkillCommandExecutionService#execute`，不新建执行框架。
请求、Header、JSON/SSE选择、错误保护及精确测试范围见
[共享命令 POST 实现](../designs/shared-command-http/README.md)。本片不迁移Events v2、控制路由或前端。

## 4. 已观察行为、目标决策和差异分类

| 主题 | `cb12ac7` 变更前源码行为或已记录历史方案 | 最终实现 | 差异分类与理由 |
|---|---|---|---|
| Web 栈 | `spring.main.web-application-type=none`；`ServerMode` 手工 Reactor Netty | 默认 Spring MVC + 虚拟线程 | 架构变更：符合正常 Spring Boot 部署和公司开发习惯 |
| 执行建模 | Java Runtime 已通过 Entry 父链恢复当前分支，没有公开 Run | 仍不公开 Run；活动执行只在服务内部存在 | 产品约束：调用方以 Session Events 区分轮次 |
| 流式连接 | 旧本地接口与公开 WebSocket 并存 | 单次 POST 建立 SSE，`stream.end` 后关闭 | 架构变更：协议唯一、断线可通过历史恢复 |
| Session 与模型 | CLI 启动时先选模型 | Session 创建不要求模型，可在后续事件前切换 | 产品约束：Session 生命周期允许模型切换 |
| 删除 | 历史方案曾计划自动 abort | active execution 返回 409；idle 才删除 | 安全加固：避免删除与执行副作用竞态 |
| 调用上下文 Header | 基线认证拦截器检查 Header 齐全、共存和 Bearer 形状；`320d7907` 把 POST Events 读取的三项值同时发送给发现和执行 | 全接口保留集成 Header 契约且不做本地认证；POST Events 读取四项值，发现请求不携带，只有 Mate Tool execute 携带 | 架构变更：真实性和动作授权由上游 mate-service 保证；安全加固：值只在当次 Agent 执行和 Child 调用期间由内存对象持有，不持久化、不依赖 ThreadLocal |
| 资源 ID | Agent 使用下划线短 ID，Session 使用无类型 Crockford Base32 | Agent/Tool/Skill/Session 使用类型前缀加 32 位无连字符 UUID；正则字符串与编译模式集中在底层 ClawConstants 的领域分组；HTTP 路径参数使用 Jakarta 注解校验 | 产品约束：阻止无前缀、错误类型和旧格式进入边界；架构变更：消除重复编译、核心代码对 HTTP 常量包的反向依赖和命令式边界 Validator |
| 创建默认值 | `RuntimeSessionService#newSession` 持久化 `thinking=false` | 创建时持久化 `thinking=true`；默认模型不支持 reasoning 时返回 `AGENT_MODEL_NOT_CONFIGURED` | 产品约束：新 Session 默认启用深度思考，且公开状态必须与模型能力一致 |
| 用户事件请求 | `UserEventRequestVO` 要求冗余 `type=user.message` | 只接受 `message` 与 `fileIds`，`type` 和 snake_case 别名作为未知字段拒绝 | 产品约束：operation 已固定消息类型，公共字段统一为 lowerCamelCase |
| HTTP 字段命名 | 路径变量、请求/响应 VO 与 SSE data 使用 snake_case | Path、Query、JSON 与 SSE data 统一为 lowerCamelCase；Header 保持原名；持久化 payload 继续使用内部格式并在输出边界受控投影 | 产品约束：对齐 HTTP 1.38，避免公共双别名并保持已有 Entry 可读 |
| thinking 事件 | Provider thinking 事件未进入公共 Runtime SSE 或持久化历史 | 执行快照为 true 时开放三阶段事件并持久化 completed；GET 按当前状态过滤 | 架构变更：显式可见性策略和可恢复事件保持一致 |
| Agent 来源 | `FileAgentRuntimeSnapshotProvider` 读取独立的 `current.json`、`revisions/{bundleRevision}` 与 `.campusagent/settings.json` 模板快照 | `AgentRuntimeManager` 准备统一受管目录；Runtime profile 默认装配 Read、Find、Grep、Ls、Cron、ListMateTools、CallMateTool 和 Agent 八个工具 | 架构变更：统一 Agent 受管目录和工具装配，删除重复模板仓库及缓存 |
| 文件 | 曾设计 Runtime 文件解析 port | `fileIds` 原样组成固定提示块 | 产品约束：文件内容不由 Runtime 下载 |
| Agent 运行根目录 | 模板快照把 `revisions/{bundleRevision}` 目录作为 Runtime 根目录 | 文件工具以 `agent/{agentId}` 为 `AgentWorkspaceBoundary`；配置、Prompt 和 Skill 位于其 `.campusclaw` 子目录 | 架构变更：分离工作区与受管配置树；安全加固：文件工具继续执行规范化路径、真实路径和符号链接边界检查 |
| 事件业务职责 | 单个 `RuntimeEventService` 同时承担接受、分页、历史恢复和异步执行收尾 | 拆分接受、查询、上下文准备和执行协调 | 架构变更：降低构造依赖和修改影响面 |
| 错误语义 | 调用点分别指定 HTTP 状态和错误码 | 错误枚举集中默认状态、i18n key 与重试时间；操作 12 的 AGENT_NOT_AVAILABLE 由集中处理器按匹配的 HandlerMethod 投影 503/Retry-After 3，其他操作保持 422 | 安全加固：默认统一映射；操作级差异必须有明确契约，不在各调用点随意指定 |
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
assistant.message.completed     finishReason=tool_call
tool.execution.started
tool.execution.completed
tool.result
assistant.message.started
assistant.message.delta
assistant.message.completed     finishReason=stop
session.status.idle
stream.end
```

连接是请求范围的，不是永久长连接。Steer/FollowUp 被活动执行接收后，从原连接继续输出；客户端断线、超时或订阅溢出不等于 abort。执行快照为 `thinking=false` 时三类 thinking 事件全部省略。

### 持久化

DDL 使用 `t_` 前缀：`t_sessions`、`t_session_tombstone`、`t_session_cleanup_task`、`t_session_entries`、`t_session_records`、`t_session_stats`、`t_session_sequences`、`t_session_materialized`。`t_session_entries` 用 `parent_id` 保留分支结构，持久化用户、助手、工具结果、thinking completed、模型/思考设置变更和 `session.compaction.completed` 等权威 Entry；Compaction Entry 记录摘要、保留边界和重试丢弃项，并参与上下文恢复。关闭 thinking 只在查询中隐藏 thinking Entry，不删除记录。SQL 在分页 `LIMIT` 前应用过滤，保证每页数量和 next page 位置一致。

助手完成和 Compaction 完成会各自追加一条 `usage` Runtime Record 到 `t_session_records`，并在同一事务内累计 `t_session_stats`。Entry 的 `entry_seq` 与 Record 的 `record_seq` 由 `t_session_sequences` 统一分配，因此两个通道共享 Session 内严格递增的持久化顺序。历史 API 只投影当前叶节点回溯得到的 Entry 分支；不参与消息分支的 Usage Record 不进入历史消息或模型上下文。

模块侧继续以 `src/main/resources/db/gaussdb/` 保存独立开发所需的全新安装与授权材料；当前产品是第一版，
不提供已有安装升级材料。公司镜像
不把它们放入 classpath，而是仅在 `scripts/install/` 交付
`initdb_gaussdbv5.sql`。同步脚本把公司数据库、Schema、Owner 与授权头部模板放在文件最前，
再从模块规范脚本生成表 DDL：过滤说明、集中 DROP 块和事务语句，并在每个
`CREATE TABLE` 紧邻前插入同表 `DROP TABLE IF EXISTS`。公司交付脚本不使用 `BEGIN`/`COMMIT`；
模块脚本保留原有事务和本地开发语义。表结构和 Runtime 行为不变；公司全新安装的连接、
归属、授权与非原子执行语义按公司交付规范调整。

事件 page 使用 AES-GCM 保护并绑定 Session、`afterSeq`、签发时 thinking 和有效期。Session thinking 切换后，旧 page 以 `INVALID_EVENT_LIST_QUERY` 失败，调用方必须从第一页重新读取。

### 容量

- 全局最多 100 个活动执行；
- 单个执行默认最多 30 分钟；
- 单个 SSE 订阅最多缓存 256 个事件或 1 MiB；
- Steer/FollowUp 队列最多 32 条或 1 MiB，超限返回 429；
- 心跳间隔 15 秒。

### Mate 工具凭据

`POST /sessions/{sessionId}/events` 读取 `X-HW-ID`、`X-HW-APPKEY`、`Authorization` 和
`access-token`。这些值只在本次 Agent 执行及其 Child 调用期间由内存中的 `MateCredentials`
持有，不写入数据库、Runtime Entry、Event、Prompt、模型消息或日志；执行结束后 Runtime
不再主动持有这些值。Runtime 不验证真实性、Bearer 形状或 AppKey/JWT 互斥性。

`ListMateTools` 以及 Call 缓存 miss 触发的 Agent binding、Skill binding、tool metadata
请求都不携带上述值。只有 `CallMateTool` 最终发送
`POST /mate-service/v1/runtime/tools/{toolId}/execute` 时，才把收到的非空值放入同名 Header；
AppKey 与 JWT 同时存在时都发送。发送 execute 前必须具有 `access-token`、`X-HW-ID` 以及
AppKey/JWT 至少一种，否则不发送 execute 请求并返回工具执行失败，但 POST Events 本身不因此
被拒绝。Cron 没有入站调用方值，因此仍可发现工具但不能执行 Mate 工具。

## 6. 验证证据

3.9.0 共享命令清单 GET 的初始实现 `afee9bd3` 完成以下验证；后续修复的验证见该实现主题记录：

- 新增 18 项真实 MVC 路由测试，复用实际 Catalog、Sources、Assembler 和 VO；与清单应用、配置路由共 44 项定向用例通过；
- `./mvnw -q spotless:apply checkstyle:check verify`：393 个测试类、1860 项测试，0 失败、错误或跳过；
- 同步四组 Java 文件及测试，镜像布局与内容一致性检查通过；公司 NativeParent 不可用，独立公司编译未验证；
- 本片没有新增 GET 的真实 JAR/openGauss 测试，也没有发布 POST Command；测试范围不代表未发布操作已完成；
- 模块侧新增 500 行、镜像后 1000 行，文档与最终 PR 门禁证据见该 PR。

3.8.0 公司 GaussDB 初始化样式完成以下验证：

- 镜像布局回归脚本验证公司头部逐字一致、镜像与暂存输出一致，且表 DDL
  与模块规范源一致；
- 生成门禁验证公司脚本不含 `BEGIN`/`COMMIT`，并且每个
  `CREATE TABLE` 均由同表 `DROP TABLE IF EXISTS` 紧邻前置；
- Bash 语法、ShellCheck、同步 dry-run、PlantUML/SVG/XML、图路径与源链接、
  PlantUML ASCII 和 `git diff --check` 通过；
- 公司 Maven compile 门禁因当前环境无法解析
  `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT` 而未通过，不宣称镜像编译已验证；
- 本次不新增 Maven 依赖，数据库表、列、索引、约束和中文 `COMMENT ON` 内容不变。

3.6.0 公司 GaussDB 脚本布局完成以下验证：

- 镜像布局回归脚本验证旧 classpath 目录不存在、公司安装目录只有目标文件，且目标与暂存 SQL
  均和模块规范 DDL 字节级一致；
- 同步脚本应用后再次 dry-run 无内容差异，Bash 语法检查与 ShellCheck 通过；
- 公司 Maven package 与同步脚本 compile 门禁均因当前环境无法解析
  `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT` 而未通过，未执行公司 JAR 内容检查；
- 主仓 Maven verify、PlantUML/SVG/XML、文档链接和 `git diff --check` 通过。

3.4.0 工具执行凭据边界在 JDK 21 上完成以下验证：

- 主仓 `./mvnw clean test`：Reactor 全部成功，其中 `campusclaw-coding-agent` 607 个测试通过，
  0 失败、0 错误；
- `campusclaw` 聚焦测试：64 个凭据链、Runtime 路由、Session/Child 测试通过，0 失败、
  0 错误；
- `./scripts/sync-campusclaw.sh`：镜像同步完成并通过镜像编译；
- `./mvnw spotless:apply`、Checkstyle、PlantUML SVG/XML、文档链接与 `git diff --check` 通过。

以下验证针对 3.0.2 候选实现执行：

- 主仓 `./mvnw clean test`：2772 个测试通过，0 失败、0 错误；
- `campusclaw` `clean test`：2772 个测试通过，0 失败、0 错误；
- `RuntimeSessionRepositoryOpenGaussIT`：连接真实 `opengauss/opengauss-server:latest`，14 个测试通过；
- `RuntimeHttpProcessOpenGaussIT`：1 个跨进程测试通过；启动打包后的真实 JVM 进程并连接真实 openGauss，覆盖创建、读取、SSE、409 删除、abort、204 删除、404 读取和墓碑；
- `./mvnw -pl modules/coding-agent-cli -am package -DskipTests`：可执行 JAR 打包通过；
- `./scripts/sync-campusclaw.sh`：镜像同步和编译验证通过；
- `./mvnw spotless:apply`：格式化检查通过；
- Checkstyle 为 0 个违规；
- 编译器语法树审计确认本次修改的 Java 方法均不超过 50 个非空物理行；
- `git diff --check` 通过。

国际化实现另外覆盖：无基础资源包的消息源上下文启动、双 Locale key 与
`RuntimeErrorCode` 完全一致、`Accept-Language` 权重与英文回退、HTTP 中文错误、SSE 中文
`stream.error`，以及 `campusclaw` 资源目录迁移和旧基础包删除。

发布前已重新执行全量测试、镜像同步、PlantUML、文档链接和 Git 校验；最终结果同时记录在发布提交报告中。

## 7. 文档策略

字段级 API 契约以独立设计仓库中的 `chat-http-v1-review.html` 为唯一评审页面；本仓只维护实现映射和运行说明。旧 `docs/openapi/campusclaw-api.yaml`、`docs/server-api.md`、WebFlux/ServerMode ADR 与公开 WebSocket 文档已删除。

## 8. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 3.11.1 | 2026-09-08 | 明确首版数据库只交付全新安装与授权材料，不提供已有安装升级入口。 |
| 3.11.0 | 2026-09-08 | 接通共享POST的Builtin JSON和Skill SSE，记录方法校验、日志及错误媒体类型边界；真实跨进程验收单独交付。 |
| 3.10.0 | 2026-09-07 | 删除无运行时消费者的旧命令原型；HTTP/SSE 和公共 Session 能力不变，POST Command 仍待实现。 |
| 3.9.0 | 2026-09-07 | 发布共享命令清单 GET，记录独立契约基线、完整缓存和操作级 503；不发布 POST Command。 |
| 3.8.0 | 2026-09-07 | 公司 GaussDB 脚本使用固定库与 Schema 头部、去除事务包裹，并将每个删表语句紧邻放到对应建表语句前。 |
| 3.7.0 | 2026-09-04 | 更新资源 ID、HTTP 路径和请求限制的归属至底层 common 的 ClawConstants，外部契约不变。 |
| 3.6.0 | 2026-09-03 | 公司镜像只在 `scripts/install/` 交付单一 GaussDB 初始化 DDL，不再将数据库发布材料打入 classpath。 |
| 3.5.0 | 2026-09-01 | 对齐 CampusClaw 公司镜像的新目录、Java 包、同步入口和独立公司构建门禁；HTTP/SSE 契约不变。 |
| 3.4.1 | 2026-08-28 | 用读取时机、内存持有期限、携带请求和缺失值行为定义 Mate 工具凭据边界。 |
| 3.4.0 | 2026-08-27 | POST Events 读取 `access-token`；发现请求不携带四项值，只有 Tool execute 携带收到的值。 |
| 3.3.1 | 2026-08-25 | 处理 PR #172 审查：校准八工具与 Agent 工作区、Compaction/Usage 持久化模型及三段源码基线 |
| 3.3.0 | 2026-08-25 | 按 Runtime-only 现状删除已退役 CLI 启动描述和失效的启动类证据引用 |
| 3.2.0 | 2026-08-24 | POST Events 读取三项 Mate 凭据并在当次 Agent 执行和 Child 调用期间由内存对象持有；保持 Runtime 不做本地认证和凭据不持久化 |
| 3.1.0 | 2026-08-21 | 对齐 HTTP 1.38：Path、Query、JSON 与 SSE data 统一为 lowerCamelCase；Header 和字段值保持原样；内部 Entry payload 通过受控投影兼容已有数据 |
| 3.0.2 | 2026-08-21 | 用 Controller 标量参数 Jakarta 注解替代命令式路径 ID Validator，并统一映射 Spring MVC 方法校验错误 |
| 3.0.1 | 2026-08-21 | 将类型化资源 ID 的正则字符串和编译模式集中到领域模式类，并从 Runtime HTTP 常量中移除领域约束 |
| 3.0.0 | 2026-08-20 | 对齐 HTTP 1.37 契约：类型化资源 ID、创建默认 thinking、移除 Runtime 本地 Header 认证、删除消息体 type，并实现 thinking 事件持久化、查询过滤和游标绑定 |
| 2.2.0 | 2026-08-20 | 落地显式双 Locale 消息源、标准语言协商、HTTP/SSE 错误国际化和镜像资源迁移 |
| 2.1.0 | 2026-08-19 | 基于 `f899547d` 整改目录边界、事件职责、错误目录、异步日志和多实例执行归属错误 |
| 2.0.0 | 2026-08-18 | 以实现提交 `8691e880` 重写，修正 MVC、鉴权边界、删除语义、Agent 目录与 `file_ids` 行为 |
| 1.x | 2026-08-18 | 逐接口开发日志，包含已经失效的 WebFlux、模板快照和文件解析方案 |
