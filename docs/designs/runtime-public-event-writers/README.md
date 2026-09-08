# Runtime v2 公共事件构造与配置写入

| 属性 | 值 |
|---|---|
| 版本 | 0.2.0 |
| 日期 | 2026-09-08 |
| 契约基线 | `pi-mono-java-design@2ee2a3211da68ad87b0d9cab353e691b00bdaebd` |
| 变更前 Java | `pi-mono-java@f5c3a755` |
| 实现提交 | `1120583b`、`f818a41f`、`2dd80793`、`d14e706d`；用量来源消费 `6c261865`、`eef4d0f1`；首版端口收敛 `bfaedc7e` |
| pi 基线 | `pi-mono@5cd93f688aaab89dbb6dfa4aca535f21796ae185` |
| 当前范围 | 公共事件工厂、字段规范化、模型/Thinking 配置事件原子写入与唯一配置更新仓储端口 |

## Context

Events v2 要求 POST SSE 的完整帧与 GET 历史读取同一份已提交公共事件。配置接口、Builtin
Command 和接受消息前的模型校准都会产生模型或 Thinking 配置 Entry。如果这些入口只写内部
Entry，历史完整性门禁会拒绝读取；如果 Service 在配置事务提交后另写公共事件，进程故障又会留下
无法修复的半份历史。

该能力建立一个公共事件工厂，并把配置状态、内部 Entry、公共事件和精确投影标记放入现有 Session
Repository 事务。首版端口收敛删除绕过公共事件的旧仓储重载，不改变配置 HTTP 响应模型或 Command 请求分派。

## 源码证据与实现边界

| 分类 | 仓库相对路径与符号 | 观察、决定和理由 |
|---|---|---|
| 已实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/event/RuntimeCommittedEventFactory.java` · `sessionConfiguration` | 以已经定稿的 `RuntimeEntryDTO` 为锚点生成 `CommittedEventDTO`；配置内部点号类型转换为 v2 公共下划线类型 |
| 已实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/persistence/RuntimeSessionRepository.java` · `updateModel/updateThinking` | 唯一配置更新端口同时接收 Entry 工厂和公共事件工厂，使配置写入只有一个事务边界 |
| 已实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/persistence/MyBatisRuntimeSessionRepository.java` · `appendConfigurationEntries` | 在 Session 行锁下分配共享序号，依次写 Entry、公共事件和精确投影标记；任一步失败回滚配置状态与全部追加记录 |
| 已实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/service/command/SessionModelConfigurationService.java` · `update` | HTTP 模型配置与 Builtin `model` Command 共用同一个公共事件写入点；模型不再支持 Thinking 时，同一事务可产生两组 Entry/公共事件 |
| 已实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/service/command/SessionThinkingConfigurationService.java` · `change` | HTTP Thinking 配置与 Builtin `thinking` Command 共用同一个公共事件写入点 |
| 已实现 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/session/RuntimeSessionModelReconciler.java` · `reconcile` | 接受消息前发现模型失效时，自动回退也写入相同的配置公共事件 |
| 已观察 Java 调用者 | `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/web/RuntimeSessionConfigurationController.java`、`modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/service/command/contributor/ModelCommandContributor.java`、`modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/service/command/contributor/ThinkingCommandContributor.java` | HTTP 与 Builtin Command 只复用配置 Service；本片没有在这些入口复制事件构造逻辑 |
| pi 观察 | `packages/agent/src/agent-loop.ts` · `runAgentLoop`、`prepareToolCall` | pi 依次发出消息、工具和轮次事件，未提供 CampusClaw 的 Session 配置资源、数据库事务或 HTTP 公共事件投影 |

公共事件表、完整性标记和配置资源是 CampusClaw 架构变化。内部点号类型服务当前模型上下文恢复，
公共下划线类型是产品 HTTP 契约；两者分离也避免公共命名改变内部 Entry 和 SQL 类型集合。

## 关键定义

- **内部 Entry**：模型恢复和 Session 状态使用的 `RuntimeEntryDTO`。它保存内部类型、父节点、内部序号与 payload。
- **权威公共事件**：由 `RuntimeCommittedEventFactory` 从已定稿领域对象生成的 `CommittedEventDTO`。
  配置事件的 `eventId` 使用锚点 Entry ID，`createdAt` 使用 Entry 时间并在写库前统一为 UTC 毫秒。
- **精确投影标记**：`t_session_event_projection` 记录每条 Entry 的公共事件数量。本片的每条配置
  Entry 恰好对应一条公共事件。
- **组合配置写入**：Repository 在一个 Spring 事务内更新 Session 配置、追加 Entry、公共事件和标记。

## 架构与数据流

![配置公共事件原子写入](configuration_event_write.svg)

[PlantUML 源码](diagram.puml#L1)

`RuntimeSessionConfigurationController` 与 Builtin Command contributor 都进入
`SessionModelConfigurationService` 或 `SessionThinkingConfigurationService`。自动模型回退从
`RuntimeSessionModelReconciler` 进入相同 Repository 端口。Service 只负责配置准入、领域 Entry
和调用编排；公共 JSON 字段由唯一工厂生成，Repository 负责锁、序号和事务。

模型切换可能同时关闭 Thinking。此时 `entriesFactory` 产生 `session.model.changed` 和
`session.thinking.changed` 两条内部 Entry，Repository 按列表顺序逐条写入对应的
`session.model_changed` 和 `session.thinking_changed` 公共事件。相同配置返回 `UNCHANGED`，不分配
序号，也不生成 Entry、事件或标记。

公共事件工厂还为执行链提供 user、agent、tool、idle 和 compacted 的统一构造入口。执行链是否已经
调用组合 append 属于执行生命周期交付；仅有工厂方法不代表该入口已经接通。工厂在边界处完成以下
已确认规范化：

- user.message 只接收调用者明确提供的安全公开文本和 fileId，不从内部 Skill 正文反推回执；
- Agent 正文只取文本块，不公开原始 Thinking；Thinking 摘要由调用者先证明来源可信；
- CallMateTool 的公开参数保持 `{tool,args}`，缺少 `args` 时补空对象，其他空工具参数使用空对象；
- 工具失败和 idle 失败保存接受时语言对应的固定公开文案，GET 不重新翻译；
- 未知 Usage 依据内部 `known` 来源标志省略，明确报告的五项零值仍保留；可选 Cost 未提供时省略。
  来源标志本身不进入公开字段，内部来源及历史 JSON 策略见 [ADR-0080](../../decisions/0080-runtime-usage-provenance.html)。

## 设计决策

见 [ADR-0081：统一公共事件工厂并原子持久化配置事件](../../decisions/0081-atomic-public-event-writers.html) 和
[ADR-0092：首版仅支持全新安装的权威事件历史](../../decisions/0092-first-release-authoritative-event-history.html)。

采用 Repository 函数参数，使公共事件只能基于已经获得父节点和内部序号的最终 Entry 构造，同时沿用
现有事务。Service 先写 Entry 再开启第二个事务无法保证故障原子性，因此不采用。把每个 HTTP、Command
或校准入口分别编码 JSON 会产生字段和类型漂移，因此也不采用。

在 `48aac44a` 基线扫描中，只有 `RuntimeSessionModelReconciler`、
`SessionModelConfigurationService` 和 `SessionThinkingConfigurationService` 三个生产消费者，且它们已全部传入
公共事件工厂。不带工厂的旧重载只剩 12 处模块测试调用，仍能写入没有权威事件和精确标记的配置 Entry。首版不存在兼容该内部端口的理由，因此删除旧重载，并在唯一端口在获取行锁和修改数据前拒绝空 `eventFactory`。

## 边界情况与 DFX

- Session 不存在、忙、资源版本不匹配或目标值未变化时，沿用既有配置状态机，不产生公共事件。
- 模型切换导致 Thinking 自动关闭时，两条配置事件使用同一锁内顺序，客户端按提交数组顺序读取。
- 公共 eventId 冲突、payload 无法构造、锚点不匹配或事件插入失败时，数据库回滚 Session 配置、Entry、
  公共事件、标记、active leaf 与共享序号。
- 每条配置 Entry 增加一条 `t_session_events` 和一条 `t_session_event_projection` 写入；同值请求没有新增
  写放大。事务不包含模型请求、工具执行或网络输出。
- 沿用 Session 主行锁和共享 sequence，不增加后台线程、缓存、网络调用或 Maven 依赖。
- 配置 Entry 保留在内部事件分支并推进 active leaf；`RuntimeEntryCodec` 不把配置 payload 当作模型对话正文。
  公共 payload 只包含契约字段，不暴露 entrySeq、parentId 或内部类型。

## 契约与交付范围

该设计使 `PUT /sessions/{sessionId}/model`、`PUT /sessions/{sessionId}/thinking`、对应 Builtin Command
以及自动模型校准共享配置公共事件写入点。配置接口仍返回原有 Session Response VO；Builtin Command
仍使用现有 JSON 响应。公共事件将在 Events v2 历史中出现，不向配置响应额外嵌入事件。

本次端口收敛只删除无事件写入能力，不改变上述 HTTP 和 Builtin Command 契约。当前产品是第一版，只使用全新安装 schema，不保留旧内部仓储重载作为兼容层。

## 测试与验证

`RuntimeCommittedEventFactoryTest` 使用真实 Jackson 投影验证安全 user 回执、正文/Usage、工具参数、固定
错误文本、未知 Usage/Cost 省略和配置类型转换。配置 Service 与自动校准测试验证组合工厂被调用、同值不
写事件、模型能力变化产生正确顺序。`RuntimeSessionRepositoryOpenGaussIT` 使用真实 openGauss 验证模型、
Thinking 与名称 Command 的差异、事件时间毫秒精度、完整性标记，以及事件主键冲突时配置、Entry 和序号
整体回滚。

writers 集成树已独立运行 121 项相关测试并通过；接入用量来源后重新运行公共工厂 6 项与压缩 14 项，
共 20 项通过。端口收敛后，配置单元测试 19 项和真实 openGauss 仓储、压缩、Usage 测试 79 项通过；新增数据库断言证明空工厂在修改 Session 前被拒绝。
Spotless、Checkstyle 和 Java AST 检查通过；ClawConstants 的 Unicode 正则解析缺口已手工核查，新增常量没有引入方法或布局问题。本次对 4 个修改 IT 的测试质量检查为零 error、15 个命名建议；建议已逐条核查，包括新增空工厂测试的异常和状态不变断言。
文档验证 PlantUML 生成、ASCII 限制、SVG XML、Markdown 链接/锚点和 `git diff --check`。
企业镜像由生成脚本同步；本地无法解析 NativeParent:26.0.0-SNAPSHOT，企业镜像编译未验证。

## 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 0.2.0 | 2026-09-08 | 删除无公共事件工厂的配置仓储重载，首版只保留原子写入端口 |
| 0.1.1 | 2026-09-08 | 明确首版全新安装范围，并保留当前版本运行时完整性门禁 |
| 0.1.0 | 2026-09-08 | 记录统一公共事件工厂、字段规范化和配置公共事件原子写入 |
