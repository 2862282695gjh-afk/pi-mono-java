# CampusClaw 计划模式实施说明

> 版本：1.4.0；日期：2026-09-17。
>
> 本次模式提示词变更前源码基线：`21c3f59be033e7bdd5506a2d9d48baa2581bc3c7`。
>
> 本次模式提示词设计基线：`pi-mono-java-design@648730b`。
>
> 既有计划模式实现提交：`17dbb20ae998c58c2b3f5ae19c254922e9f93210`；本轮延续分支合并基线：
> `79cfa34daef20adfa13ae1bee7cc9c41ba5c9dc7`。
>
> 本轮 Claw 前端对齐源码基线：`9f785bea3ffff8f6566cd1b2727285af46a2aead`；前端体验设计证据：
> `pi-mono-java-design@9504bf3`；计划/问答设计证据：`pi-mono-java-design@c296624`。

## 范围与设计来源

本实现覆盖 Runtime HTTP、执行编排、数据库记录、固定提示词、三个内置工具和调试前端。
整体目标类图、交互流程及提示词规范由
[计划模式主设计](https://github.com/superheromeZzh/pi-mono-java-design/blob/497d34a4f05def36d4e3c62085d9eb4848b70f69/01-总体架构/06-CampusClaw计划模式/README.md)
维护；精确 HTTP 字段以其中链接的共享契约为准。

变更前基线已经有消息受理、普通工具确认、跨实例控制、请求范围 SSE 和公共历史。
计划模式复用这些生命周期边界，新增计划等待与确认、结构化问答、Session mode 和任务快照投影。
本说明不将历史基线登记等同于上游源码重新核验，也不对 Claude 托管服务内部实现作推断。

## 逐题交互与提示词增量

设计仓 `3e3d569` 的新交互实施说明见[开发设置与 Build/Plan 交互](../designs/frontend-experience.md)。
问题卡一次展示当前一题，前后导航只保存页内草稿，末题仍通过既有 `user.question_answer` 一次提交全部答案。
提问调用/结果的主对话展示由权威问题和答案卡吸收，模型工具结果与持久化记录继续保留。
Plan 提示词先探索可查事实，只有影响方案的未知才提问，优先一题；不把回答或普通肯定解释为批准。
本增量不改变原根执行、三类等待、数据库控制事务或公共 Schema。新验证单独记录，不将下方历史通过数延伸到新代码。

## 代码接线与状态边界

以下 Java 路径均相对 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`。

| 职责 | 源码路径与符号 | 实现行为 |
|---|---|---|
| Agent 默认模式 | `runtime/MateServiceClient.java#AgentRuntime`、`runtime/AgentModeDeserializer.java`、`runtime/AgentRuntimeManager.java#toSettings` | mode 为严格字符串，缺省 build，显式 null 或非枚举值无效；发布至受管设置快照 |
| Session 创建与读取 | `runtimeapi/session/RuntimeSessionService.java#newSession`、`RuntimeSessionResponseAssembler.java` | 新 Session 复制 Agent 默认模式；完整响应带 mode，已有 Session 不跟随 Agent 更新 |
| 统一配置 | `runtimeapi/session/RuntimeSessionUpdateService.java#update`、`runtimeapi/persistence/MyBatisRuntimeSessionRepository.java#updateSession` | prepare 在锁外；锁内核对版本、状态和实际变化，原子提交配置及事件 |
| 提示词快照 | `runtimeapi/agent/RuntimeAgentPromptLoader.java#loadSnapshot`、`AgentPromptSnapshotDTO.java`、`runtime/AgentRuntimeManager.java#prepare` | 启动时校验两份内置模式资源；同一 Agent 锁内固定 BASE 与逐文件覆盖选择，业务文件仅在真正不存在时回退 |
| 提示词组合 | `session/PlanPolicyPromptComposer.java#compose`、`session/ManagedAgentSession.java#applyMode` | 只消费执行准备时的不可变快照；批准、修订、重复切换和压缩不重新读盘或累计模式正文 |
| 入口装配 | `tool/builtin/BuiltInToolProperties.java`、`session/AgentSessionFactory.java#create` | Runtime 默认包含 ExitPlanMode/TodoUpdate；plan/build 工具定义一致；Child/Cron 缺省 profile 保持既有集合 |
| 实际执行模式 | `runtimeapi/event/RuntimeMessageEventService.java#startAccepted`、`runtimeapi/runtime/RuntimeSessionEngineRegistry.java` | 使用消息接受事务返回的 mode，启动前应用；批准后在返回工具结果前应用 build |
| 计划工具 | `tool/plan/ExitPlanModeTool.java`、`tool/plan/PlanReviewPort.java` | 工具只等待窄端口决定，不依赖数据库或 HTTP；返回批准或修订结果 |
| 审核等待 | `runtimeapi/event/RuntimeEventProjector.java#beginPlanReview`、`runtimeapi/runtime/RuntimeActiveExecution.java` | 验证根执行 plan、原 Assistant 仅一个 ToolCall；保存计划与 confirming，关闭当前响应并等待 |
| 结构化问题 | `tool/question/QuestionRequestParser.java`、`AskUserQuestionTool.java`、`runtimeapi/event/RuntimeEventProjector.java#beginQuestion` | 只解析一次严格参数并共享不可变快照；保存原工具调用后原子发布完整问题与 confirming |
| 统一等待槽 | `runtimeapi/runtime/RuntimePendingInteraction.java`、`RuntimeActiveExecution.java` | 工具权限、计划审核和用户问题同根严格互斥；先绑定续段输出再唤醒 Future，保留早到答案窗口 |
| 接受与交付 | `runtimeapi/event/RuntimeControlEventService.java#acceptPlanConfirmation`、`runtimeapi/persistence/MyBatisRuntimeExecutionControlRepository.java`、`runtimeapi/runtime/RuntimeSessionControlDispatcher.java` | 决定、新段和批准模式原子提交；数据库 claim/ack 向原执行交付，续段首帧为决定回执 |
| 问答接受与交付 | `runtimeapi/event/RuntimeUserQuestionService.java`、`RuntimeControlEventService.java#acceptQuestionAnswer`、`RuntimeExecutionPersistenceService.java#acceptQuestionAnswer` | 锁内先判停止和精确等待，再按问题快照规范化；答案回执、交接和新段整笔提交，原 owner 续跑 |
| Child 模式 | `tool/agent/SubagentExecutionContext.java#mode`、`SubagentExecutionService.java` | 在实际 Child 调用时复制父模式，不共享审核 Future；后续 Child 可看到已批准后的 build |
| 实施任务 | `tool/todo/TodoUpdateTool.java`、`runtimeapi/event/RuntimeTodoEventMapper.java`、`RuntimeEventProjector.java#persistToolResults` | 校验完整清单；在 after hook 后重新解析最终成功 JSON，再生成任务事件 |
| 公共投影 | `runtimeapi/event/CommittedEventProjection.java`、`RuntimeCommittedEventFactory.java` | 实时完整事件与历史共用公共形状，审核 Entry 不额外生成模型对话消息 |
| 调试前端 | `frontend/src/composables/useRuntimeApi.ts`、`useWorkbenchSessions.ts`、`runtime/sessionIndex.ts`、`runtime/questionInteraction.ts`、`components/ComposerBox.vue`、`QuestionCard.vue`、`PlanCard.vue`、`AppSidebar.vue` | 实时与历史共用严格 decoder/reducer；模式入口、问题/方案卡和 Dock 完成键盘及折叠交互；SessionIndex v2 分离活动与置顶；名称更新复用统一 Session/ETag |

底层 `modules/agent-core/src/main/java/com/campusclaw/agent/tool/AgentContext.java#systemPrompt` 读取同一 AgentState，
`modules/agent-core/src/main/java/com/campusclaw/agent/loop/AgentLoop.java` 在每次 Provider 请求时组装 Context；因此批准后的 Prompt 更新可进入
原执行的下一次模型请求。工具参数错误沿既有 Pipeline 转换为稳定工具失败，允许模型修正。

### 模式提示词快照与业务覆盖

classpath 只保留 `prompts/plan/PLAN.md` 和 `BUILD.md`。Runtime Main Agent 在 plan/build 中分别使用
`BASE + 当前执行角色：Main Agent。 + 同名模式正文`；Child plan 使用自身 Agent 快照中的
`BASE + 当前执行角色：Subagent；只向父 Agent 返回结果。 + PLAN.md`。Child build 与 Cron 保持 BASE，
不追加模式正文。Composer 不按 AskUserQuestion 或 TodoUpdate 是否装配裁剪文件内容。

每个业务 Agent 可以在自己的 `.campusclaw/PLAN.md`、`.campusclaw/BUILD.md` 中逐文件完整覆盖内置正文。
覆盖文件不存在时仅对应模式回退；空白、目录、符号链接、非法 UTF-8、超过 1 MiB、无权限或读取失败都
使准备失败，不能借重建删除错误文件后回退。刷新和重建保留两份合法可选文件，首次目录只有模式文件时也
保留。当前执行持有 `AgentPromptSnapshotDTO`，磁盘后续变更只影响下一次新执行。

### 计划制作与执行

1. 新会话读取 Agent.mode；用户也可在 idle 状态通过 Session POST 切换模式。
2. plan 根执行读取、思考和调研，提示词禁止开始实施或记录调研进度。
3. 模型单独调用 ExitPlanMode，提供完整 Markdown。首个非空行必须在原文本中以 `# ` 开始；
   保留原文，校验 UTF-16 长度及编码后的事件容量，不静默截断。
4. Runtime 先保存定稿工具调用，再原子保存 `agent.plan`、confirming 与等待身份。
   当前 SSE 关闭，Session 保持 running/plan，不提前生成 ExitPlanMode 的成功结果。
5. 用户提交该计划 eventId 的批准或修订。修订仍属原根执行，新方案使用新事件 ID；旧方案不能再次审核。
6. 批准使 Session 进入 build。原工具收到决定，后续正常思考、工具调用与答案继续使用同一根消息。
7. 多步骤实施通过 TodoUpdate 提交完整清单，完成工作和必要验证后及时更新；实际调用频率由
   提示词与工具说明约束，不把模型遵循提示词表述为硬性调度保证。

完整计划保存在定稿 ToolCall 和权威 `agent.plan` 内容中，没有另设计划文件、planId 或计划版本资源。
公共 Session 不含 pendingPlanEventId；该指针只在内部执行记录中用于审核一致性。

### 结构化问答

Runtime 默认工具列表在既有十项之后追加 `AskUserQuestion`；显式 profile 不强制补齐。Child 和 Cron
默认不装配，显式配置时以 `USER_QUESTION_UNAVAILABLE` 拒绝。plan/build 使用相同工具名称、说明、Schema
和顺序，回答不批准方案、切换 mode、替代业务工具权限或重置 TODO。

工具一次询问 1～3 题。Parser 拒绝未知字段、null、重复 id/label 及不符合选项数量的对象；展示文本和
custom 先按 Java `String.strip` 规范化，再按 UTF-16 长度校验。模型工具只等待 callId 对应结果，成功时
返回一个 JSON 文本块。公共 `agent.question` 和 `user.question_answer` 作为展示与控制记录，不重复进入模型历史。

问题、confirming 和内部指针在同一事务提交。回答使用精确 `targetEventId`，锁内按 Session、执行和段顺序
重新检查停止与当前问题，再按原问题和原选项顺序生成规范答案。答案回执、专用交接记录、新段和指针清理
原子提交；等待、停止和超时均保持原 root、mode 与执行凭据。答案交接表只引用已提交事件，不复制答案 JSON。

### 任务事件与失败行为

TodoUpdate 是无状态 SEQUENTIAL 工具，返回一个规范 JSON 文本块。todos 是最多 100 项的完整快照；
content 按 Java strip 规范化、非空且不重复，长度最多 1024 个 UTF-16 单位；状态限于 pending、
in_progress、completed，同时最多一项 in_progress，空数组表示清空。

后端只识别已定稿的内置 TodoUpdate 调用。普通失败结果不改变清单；已知成功结果经过 after hook 后，
必须仍是完整合法 JSON，尾随文本、重复字段或多个 JSON 根均拒绝。普通 tool result 与
agent.todo_updated 使用不同事件 ID、同一个 Tool Result Entry，在同一事务内提交后发送。

前端不从工具参数或结果文本推断清单。新根消息重置进度；断线按历史与 live 事件对账；执行结束
保留最后快照。清单全部完成不代表根执行已结束，最终状态以根执行终态事件为准。

### 生命周期与集成代价

审核等待占用原执行，并受原执行 maxDuration 约束。停止优先于未领取的决定；批准与停止或超时
交错时，终态须写入该根执行当前的权威段。执行结束后才释放 Session，避免接受下一条消息时
旧执行仍有副作用。原进程丢失时不自动接管，也不为恢复界面自动重放写请求。

这些数据库事务与后端任务投影属于架构变更，用于统一多客户端与恢复后的可见结果；强版本校验、
目标关联和停止竞争处理属于安全加固。稳定元数据及仅记录实施进度属于产品约束。计划策略通过
Prompt 实现，现有业务工具权限继续生效，不能据此宣称 plan 是强制只读沙箱。

没有新增 Maven 或前端直接依赖。两份固定模式提示词位于
`modules/coding-agent-cli/src/main/resources/prompts/plan/`，由同步脚本一并进入公司镜像；
数据库变更只进入首版全量安装 Schema；产品尚未发布，不提供迁移或升级脚本。模块 SQL 保留原有清理段；
公司生成 SQL 沿用每张表一次 `DROP TABLE IF EXISTS` 紧邻 `CREATE TABLE` 的格式，不复制模块的集中清理段。

## 外部工程边界

本轮按用户明确范围只实现 Claw，不接入 `mate-service`。本仓没有企业 `mate-service` 公共 Chat Controller 和适配实现。外部工程仍需按共享契约接入
Agent.mode 元数据、统一 Session 更新、计划确认和结构化问答，并完成企业认证、租户、审计和
真实业务工具联调。公共 Chat 适配必须把外部会话身份映射到 Runtime 资源，不能把内部 sessionId、eventId、
toolCallId 或执行凭据直接透传给浏览器。Runtime 的生产接线和内部调试工作台可交付，不表示这些企业适配已实现。

### BASE 通用行为发布待办

本仓没有受版本管理的业务 `SYSTEM.md`；`AgentRuntimeManager` 将 Mate 返回的 `AgentRuntime.systemPrompt`
发布为各 Agent 的受管 `SYSTEM.md`。因此下面三段必须由 Mate 业务 Agent 配置随本次迁移发布，本仓不能把
它们当作已经上线，也不能据此声称 P15 已通过：

```text
在当前模式和授权范围内完成任务。先检查已有信息与工具能力，沿用已明确的目标和约束；可查询的事实自行查明，区分事实、假设和建议，不编造工具能力或执行结果。

只为无法查明且影响下一步的信息或重要选择提问，优先一个问题；仅在独立问题必须同时解决时合并。Main Agent 使用可用的提问工具；工具未提供且必须回答时，用普通回复提问并结束，Subagent 将问题交给父 Agent，Cron 汇报缺失信息。无待决信息就继续，纯解释或状态查询直接回答。

执行后完成必要验证，简洁说明实际结果、验证情况与未解决事项。失败、部分完成和无法验证时如实报告；工具返回成功或进度显示完成不能代替任务验收。工具结果用于继续工作，不将原始参数或 JSON 当作最终答复。
```

外部发布按以下步骤验收：

1. 在 Mate 中把上述三段原文合入每个业务 Agent 的 `systemPrompt`，同时保留其身份和领域能力。
2. 在部署窗口通过受控 Java 运维调用执行既有 `AgentRuntimeManager.refresh(agentId)`，或由部署方使用
   既有受管目录发布流程；本仓没有公开刷新 API。确认新生成的 `.campusclaw/SYSTEM.md` 含三段完整文本，
   且合法的业务 `PLAN.md`、`BUILD.md` 仍保留。
3. 刷新后创建一条新执行。用受控 Provider 捕获最终 `Context.systemPrompt`，确认 BASE 三段各出现一次，
   再按入口核对 Main Agent 角色和 PLAN/BUILD 正文；不要用刷新前的活动执行代替验证。
4. 分别验证 plan、build、缺 AskUserQuestion/TodoUpdate、Subagent plan、Child build 和 Cron，确认不存在
   通用行为缺口或重复注入。记录 Agent ID、refresh 结果、新执行 ID 和捕获文本摘要。

完成以上外部步骤后才能关闭 P15；本仓单元测试只证明给定 BASE 会完整且不重复地进入模型请求。

## 验证

Java、数据库、JAR、公司镜像及原 172 项前端验证对应 `9f785bea`；后续 Claw 前端交互补齐为 `fcae53829c2884ea1d3cc522c183f5a125a67f45`，独立复核其 189 项前端测试、类型检查和生产构建均通过。该固定版本当时没有重跑未修改的 Java 与数据库。前端浏览器验收使用合成后端，
JAR HTTP 测试使用受控模型响应，均不等同于企业 mate-service 或真实模型遵循率验收。

| 范围 | 必须证明的内容 | 当前记录 |
|---|---|---|
| Java / 格式 | JDK 21 编译、单元回归、Spotless、main/test Checkstyle、方法长度与边界规范 | 全 Reactor `clean verify` 通过：373 类、2085 项，0 失败、错误、跳过；Spotless/Checkstyle 复查通过 |
| 真实数据库 | 模块 Schema 重复安装；审核/问答交接、事务回滚、并发、停止和超时竞争 | 专用 openGauss 7.0.0-RC3：16 个 IT 类 140 项通过；新增问答 IT 4 项含真实约束失败整笔回滚；不宣称生产 GaussDB 验证 |
| 实际 JAR HTTP | question → answer → 原 plan 根 → approve/build/TODO；并发回答、停止及历史同形 | 受控模型与真实 JAR/openGauss 探针通过；模型历史只含原 user 与可信 tool answer，问答展示事件未重复注入 |
| 前端 | 严格协议、问题卡、模式与 Dock 交互、会话索引迁移、直接改名、重复提交和未知写入不重发 | `npm test`：19 文件、189 项通过；`npm run build` 通过；项目没有独立 lint script |
| 独立契约验收 | 160 个共享帧、50 个字段/Unicode 边界、4 个 Java 实际投影、10 个 reducer 场景及浏览器交互 | 214 个 decoder 契约、10 个状态场景和 18 项浏览器验收通过；合成浏览器验收不替代企业公共 Chat |
| 公司镜像 | 代码、SQL 和模式 Prompt 同步；公司头不变，每张表仅一条 DROP 且紧邻 CREATE | 历史版本的 `sync-campusclaw.sh --no-verify` 及布局检查通过；公司编译因 `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT` 无法解析而受阻，未进入源码编译 |

本次 1.4.0 变更的验证记录以实际分支日志为准：JDK 21 `test-compile` 与模式提示词定向测试通过；
定向测试覆盖 Loader、Manager、Spring 装配、Composer、Factory/ManagedSession、Runtime Session、Child、
Cron、ExitPlanMode 和 TodoUpdate，共 118 项，失败、错误、跳过均为 0。Spotless、main/test Checkstyle、
JDK 21 编译、公司镜像同步和布局检查通过。公司镜像源码编译在读取项目模型前因本地无法解析
`com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT` 而停止，未宣称编译通过。BASE 三段的 Mate 发布和
新执行受控 Provider 验收仍是外部待办，P15 尚未通过。

前一固定版本没有可用的 `check_test_quality.py`，仅人工核对测试。本次使用本机历史审查目录中的脚本副本
辅助扫描 25 个变更测试文件：10 个 no-assertion 报告均由断言辅助方法或 AssertJ 异常链造成，100 个命名
警告不符合本仓既有命名约定，人工复核后实际问题为 0。AST 检查之外，Unicode 转义和 record/interface
的排版另外人工核查；浏览器验收没有模拟系统输入法候选窗，但问题卡源码没有 Enter 提交处理。
编译或测试通过不能替代分层、状态竞争和契约审查。

## 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 1.0.0 | 2026-09-15 | 接入 Session 模式、完整计划审核与实施进度事件，记录运行时和企业适配边界。 |
| 1.1.0 | 2026-09-15 | 接入严格结构化问答、跨实例答案交接和可恢复问题卡。 |
| 1.2.0 | 2026-09-15 | 完成 Claw 前端模式菜单、问题与方案卡、Dock 折叠、会话索引及 Sidebar 交互对齐。 |
| 1.2.1 | 2026-09-15 | 公司初始化脚本每张表只保留建表前的一条 DROP，并校验无额外清理段。 |
| 1.3.0 | 2026-09-16 | 逐题问答保留整批回答事务，提问工具展示汇聚到专用卡，并精简 Plan 澄清策略。 |
| 1.4.0 | 2026-09-17 | 两份模式文件支持业务 Agent 独立覆盖并固定为执行快照，同时明确 BASE 外部发布验收。 |
