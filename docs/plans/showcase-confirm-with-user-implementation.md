# Showcase 业务确认开发与演示

版本：1.0.4；日期：2026-09-23。仅用于 `codex/showcase-confirm-with-user` 分支，不合入 main。

实现起点为 `19870445a3a8bd559919fe24580330be8227488f`。设计依据为
[showcase 业务确认设计](https://github.com/superheromeZzh/pi-mono-java-design/blob/106f9f5a79af78386717371e1ba52330d26013bf/01-总体架构/06-CampusClaw计划模式/showcase-confirm-with-user.md)；
事件字段、原样载荷与答案规则以该设计链接的共享契约为准。本说明记录实现与演示操作，不复制公共接口 Schema。

## 分支行为

该分支启用业务确认适配，不增加 showcase 专属开关。后端与该分支前端必须配套发布。
内置工具按 `campusclaw.tools.runtime` 清单装配；showcase 打包配置默认不含 AskUserQuestion，显式加入即可启用，装配器不再强制过滤。
通用 Java 默认工具清单保留 AskUserQuestion；分支默认差异仅在 standalone `application.yml` 与公司 `application.properties` 中表达。
AskUserQuestion 与已绑定的 confirm_with_user 可以并存，分别用于普通澄清和业务确认；业务工具绑定不决定内置提问是否装配。

需要恢复内置提问时，在外部配置中提供完整 Runtime 工具清单（列表覆盖而非追加）：

```properties
campusclaw.tools.runtime=Read,Find,Grep,Ls,Cron,Skill,SearchTools,CallTool,Agent,ExitPlanMode,TodoUpdate,AskUserQuestion
```

配置在部署启动时绑定，修改后重启。未配置 AskUserQuestion 且未绑定可用 confirm_with_user 的展示 Agent 没有问题卡入口；需要普通提问的部署应显式启用 AskUserQuestion。

独立服务可用以下参数启动，数据库、模型及 Mate 连接仍使用已有部署配置：

```bash
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar
```

公司镜像不需要 showcase 专属配置，具体启动入口沿用公司部署流程。该分支没有新增数据库表、字段或 Maven/npm 依赖。

在业务 Agent 的可信工具绑定中配置精确名称 `confirm_with_user`，权限必须为 allow，工具 ID 必须明确。
Agent 与多个 Skill 可以重复绑定同名同 ID 工具，不按绑定条数误判歧义。可信解析拒绝不同 ID 或无效 ID；执行授权按入口分别检查 Agent 或 Skill 的绑定范围，同一授权范围内的权限冲突仍拒绝。
输入 label 为必填字符串，context 为可选对象；context 内部不约定字段。Agent 直接关联时，模型直接调用 confirm_with_user；仅通过 Skill 关联时，经 CallTool 调用它。旧 CallMateTool 仅作为历史事件名称保留，不恢复为可配置工具。
原始 label/context 在问题事件及界面保持 JSON 值与结构相同，省略 context 和空对象保持区别。
确认只在 Runtime 根执行中等待用户回答；Child/Cron 不执行这个本地交互，也不降级远调。

业务 Agent 的 BASE 和自定义 PLAN.md/BUILD.md 如有旧提问指令，应按以下行为更新，保留业务身份和其他模式约束：

```text
需要用户提供信息时使用实际可用的提问能力。普通澄清可使用 AskUserQuestion；业务流程要求业务确认时调用已绑定的 confirm_with_user：Agent 直接关联时直接调用，仅通过 Skill 关联时经 CallTool 调用，传入原始 label 和可选 context。业务提问时该次 Assistant 只发出这一项工具调用，等待工具返回真实 content 后再继续。Subagent 把需要用户回答的问题交给父 Agent；Cron 汇报缺失信息。
```

## 源码接线

本次 main 同步基线为 `c04bbdc49b88fb12d0e5db68be84036c05730476`（2026-09-22）；只迁移现有确认能力到新工具入口，不实施讨论中的 BusinessConfirmationAdapter/Dispatcher 解耦方案。

下列 Java 路径相对 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`。

| 职责 | 路径与符号 |
|---|---|
| 工具清单配置与直接装配 | `tool/builtin/DefaultConfiguredToolAssembler#assemble`、standalone `application.yml`、公司 `application.properties` |
| 每执行适配器与可信绑定 | `runtimeapi/runtime/RuntimeSessionEngineRegistry#createSession`、`tool/question/ShowcaseConfirmationAdapter#prepare` |
| 原问题等待与失败清理 | `runtimeapi/runtime/RuntimeActiveExecution#beforeToolCall`、`runtimeapi/event/RuntimeEventProjector#beginQuestion` |
| 可信解析、参数校验与本地结果 | `tool/mate/MateToolResolver`、`MateToolAdapter#prepare`、`CallTool#prepare`、`MateToolExecutor#executePrepared`、`ShowcaseConfirmationAdapter#await` |
| 原样事件及历史 | `runtimeapi/event/RuntimeEntryCodec`、`RuntimeCommittedEventFactory`、`CommittedEventProjection` |
| 固定答案槽校验 | `runtimeapi/event/RuntimeUserQuestionService#normalizeCurrent`、`tool/question/QuestionRequestParser#normalizeTextAnswer` |
| 前端卡片与提交 | `frontend/src/components/QuestionCard.vue`、`runtime/questionInteraction.ts`、`projectors/runtimeEventProjector.ts`（前端路径相对仓根） |

## 人工验收步骤

1. 使用分支默认配置新建执行，确认模型工具清单无 AskUserQuestion；外部配置显式加入后重启，确认可调用 AskUserQuestion，其他业务工具和 confirm_with_user 仍按各自规则执行。
2. 让模型调用 confirm_with_user，分别覆盖省略 context、空对象及嵌套数组/null/空白值。
3. 核对 agent.question 与原工具 args 的 label/context 深相等；原 SSE 在 confirming 后结束，Session 仍为 running，界面显示等待回答。
4. 核对界面只显示该问题的 label/context、回答框和确认按钮；普通业务工具仍显示；直接 confirm_with_user、CallTool 与旧 CallMateTool 历史按关联问题汇聚。
5. 输入具体业务数据并点击确认。核对 user.question_answer 回执先于原工具结果，结果 content 为规范化后的真实回答，原根继续执行。
6. 覆盖空回答、重复点击、停止、断线恢复、其他客户端已回答和再次提问；旧题不可重复回答，草稿不串题，写请求不自动重放。
7. 分别检查宽屏和窄屏布局；Enter 换行，输入法确认不触发提交，长 context 和按钮可滚动到达。

工具绑定为 deny/ask、缺失或歧义时应明确失败，不产生问题等待，不调用远端同名工具。
用户回答不批准计划、不改变 mode、不创建新根，也不授予其他业务操作权限。

## 失败诊断

诊断实现起点：`40fa05f99fe1b3bb4363e0918b1ab0a63d6939ae`，诊断实现提交：`d8de4e195caba66c75eb31919f009dce76fbcb6c`。问答失败在 `ShowcaseConfirmationAdapter`、`RuntimeEventProjector#requireUniqueFinalizedCall` 和 `RuntimePendingInteraction` 的失败分支记录 ERROR 日志，并通过 `UserQuestionException` 携带独立错误码。`RuntimeToolPermissionPolicy#diagnoseBinding` 保留按工具 ID 聚合的诊断兼容接口，同 ID 重复绑定不再拒绝；新执行使用主线 `PreparedToolInvocation` 的可信目标和入口范围授权，不以聚合诊断代替授权。

日志正文和结构化字段均包含 `errorCode`、`operation`、工具调用标识及可用的 sessionId/agentId。发现异常保留 cause；诊断字段不包含 label/context、答案或凭据。工具 Pipeline、实时 `agent.tool_result.errorCode`、持久化和历史查询保留具体码值；客户端显示对应的中英文安全文案。旧 `USER_QUESTION_UNAVAILABLE` 仅保留兼容历史。

下表列出可读取的问答错误码，包含旧版本历史兼容。当前工具绑定解析、元数据查询和 Schema 校验已前移至主线通用准备阶段；适配器不再二次查询工具。旧 `BINDING_MISSING`、`BINDING_AMBIGUOUS`、`DISCOVERY_FAILED`、`TOOL_NOT_FOUND`、`TOOL_ID_MISMATCH` 不再保证由当前确认适配器产生，相应前置失败沿用主线工具解析异常。

| 错误码 | 含义 |
|---|---|
| `USER_QUESTION_ENTRY_UNSUPPORTED` | 当前执行入口不支持向用户提问。 |
| `USER_QUESTION_PORT_MISSING` | 用户提问等待端口未就绪。 |
| `USER_QUESTION_ADAPTER_UNBOUND` | 用户确认适配器尚未完成初始化。 |
| `USER_QUESTION_BINDING_MISSING` | 当前 Agent 或 Skill 缺少有效的用户确认工具绑定。 |
| `USER_QUESTION_BINDING_AMBIGUOUS` | 用户确认工具的同名绑定指向不同 ID，无法确定唯一工具。 |
| `USER_QUESTION_PERMISSION_DENIED` | 用户确认工具的绑定权限不允许调用。 |
| `USER_QUESTION_PERMISSION_REQUIRED` | 用户确认工具配置为需审批，当前确认入口要求 allow 权限。 |
| `USER_QUESTION_DISCOVERY_FAILED` | 查询用户确认工具失败，请根据工具调用标识检查服务端日志。 |
| `USER_QUESTION_TOOL_NOT_FOUND` | 工具发现结果中未找到用户确认工具。 |
| `USER_QUESTION_TOOL_ID_MISMATCH` | 用户确认工具的发现 ID 与绑定 ID 不一致。 |
| `USER_QUESTION_PREPARATION_CONFLICT` | 已有另一项用户确认调用正在准备或等待。 |
| `USER_QUESTION_NOT_PREPARED` | 用户确认调用尚未完成问题准备。 |
| `USER_QUESTION_CALL_MISMATCH` | 用户确认调用与已记录的调用不一致。 |
| `USER_QUESTION_ANSWER_INVALID` | 用户确认返回的答案结构无效。 |
| `USER_QUESTION_MULTIPLE_TOOL_CALLS` | 提问必须是当前 Assistant 消息中唯一的工具调用。 |
| `USER_QUESTION_CALL_NOT_FINALIZED` | 用户确认调用尚未记录为完整工具调用。 |
| `USER_QUESTION_PENDING_CONFLICT` | 当前执行已有未完成的交互，无法创建新的用户问题。 |
| `USER_QUESTION_WAIT_MISSING` | 找不到当前工具调用对应的用户问题等待。 |

参数校验失败使用 `TOOL_ARGUMENTS_INVALID`，`isError=true`；主线 Pipeline 向模型反馈字段错误与本次校验使用的可信定义，公共用户事件只显示安全文案。适配层仍对 label/context 类型兜底，日志 `reason` 区分 `label_not_string`、`context_not_object`；通用 Schema 校验先于此层执行，不能用缺少适配层 reason 推断参数正确。

部署包含此改动的新构建后，可按失败时段检索实际日志目录：

```bash
rg -n -C 10 'User question failed:|USER_QUESTION_|runtime.user-question.confirmation' /actual/log/directory
```

按日志中的 toolCallId 和 sessionId 缩小范围。发现失败还可检索 `listAgentTools failed` / `listSkillTools failed` 的底层日志。新增日志不能补回旧部署未记录的历史原因；无需额外开启 DEBUG。

## 退出演示

停止接受新展示执行，完成或中断已有等待，再切换回正式构建。
含 label/context 的历史继续要求联合载荷解码；不把新历史交给只认识 questions 的旧二进制，不清表回退。

## 验证记录

2026-09-23：showcase `c8f964b10af1b73018ce79cd4e6f060c43362344` 合入 main `c04bbdc49b88fb12d0e5db68be84036c05730476`，确认功能接入 Agent 直接代理和 Skill CallTool 的可信准备链，保留主线线性 Session 历史。

- JDK 21 定向回归 27 类 228 项全部通过，无失败、错误或跳过；覆盖工具准备顺序、两入口本地回答与零远调、权限范围、配置覆盖、问答和历史投影、等待清理、计划审核及 Session 装配。
- 前端相关 7 文件 182 项通过，typecheck、生产构建与 fixture 语法检查通过；未重新执行受监听端口限制的开发认证/代理测试。
- Spotless、Checkstyle、主模块 package 与公司镜像同步/布局检查通过；Java AST 扫描无方法长度或版权问题，合并适配涉及的 record 布局已人工核对。测试质量扫描的 5 项无断言提示均已核实为断言 helper/AssertJ 识别误报，未改写主线测试以迎合扫描器。
- 公司 NativeParent 构建、真实 Mate/openGauss 和浏览器端到端演示本次未重跑；以下闭环记录保留历史归属，不作为本次同步后的端到端验收。

2026-09-21：在 showcase `d8de4e195caba66c75eb31919f009dce76fbcb6c` 基础上合入 main `d1cdb708e3e124cae009b10b985f5832b6dbde01`，保留正文计划审核、工具展示名初始化和可选凭据透传。配置与重复绑定修复随本文件同一提交交付；核验范围为上述工具装配、`RuntimeToolPermissionPolicy#diagnoseBinding`、两个打包配置及模式提示词。

- 合并后 20 个定向后端测试类共 176 项通过，涵盖打包默认禁用与外部配置启用、同 ID 跨来源重复绑定、不同 ID/权限冲突、两类问题、计划正文审核、展示名和错误投影。
- Spotless、Checkstyle、package、修改 Java 类的 AST 方法长度与版权检查通过；数据载体 record 的布局已人工核对。测试质量扫描 errors=0，命名提示已人工核对。
- 前端源码与此前核验的自动合并树 `9cae7bd5f27c4e49a55fe40ad3d9689d6419773f` 的 89 个源码/脚本文件逐字节一致，沿用其 186 项相关测试、类型检查和构建证据；未将受本地端口限制的 10 项认证代理测试记为通过。
- 公司镜像已由模块源码同步；公司 NativeParent 构建与真实 Mate/openGauss 集成未在本次重跑。下表保留原展示闭环的历史证据，不代表本次合并后完整验收。

2026-09-18 对最终代码完成以下验证。未新增或修改图源，沿用设计分支的目标结构与时序图。

| 范围 | 结果 |
|---|---|
| 后端定向回归 | JDK 21，15 类 118 项，0 失败、错误、跳过；覆盖各失败分支的细分码、诊断日志与 cause、18 种码的工具管线/双语实时事件/历史投影、旧 CallMateTool、工具装配、等待清理及 HTTP 输入边界 |
| 实际 HTTP / AgentLoop / openGauss | 3 项通过，独立合成测试库且未传 showcase 配置；确认闭环、原根续跑、等待无后续模型请求/远端执行、context 变体、4096/4097 答案边界及停止 |
| 前端 | 原功能基线 34 文件 266 项测试、类型检查与生产构建通过；本次协议和投影定向回归 2 文件 115 项通过，包含 18 种新失败码的实时与历史读取 |
| 浏览器 | 1440px/390px 原样问题、可滚动按钮、空回答提示、Enter 换行、确认续跑及刷新恢复通过；未模拟系统输入法候选窗 |
| 前后端实际载荷 | 实际 JAR 的 10 个 live 帧和 9 个历史帧通过前端 decoder/projector；保留 label/context，只有一张关联问题卡 |
| 静态规范 | Spotless check、main/test Checkstyle、本次修改/新增 Java 文件的 AST 方法长度检查、git diff --check 通过 |
| 测试质量 | 扫描 errors=0；命名启发式警告及既有 catch 重抛提示已人工核对，无吞异常或恒真断言 |
| 公司镜像 | sync-campusclaw.sh --no-verify 与布局检查通过；公司构建无法解析 NativeParent:26.0.0-SNAPSHOT，未进入源码编译 |

主模块 package 成功，产物位于 `modules/coding-agent-cli/target/campusclaw-agent.jar`；前端构建产物位于 `frontend/dist`。
超长答案的实际 HTTP 测试暴露了 POST events 方法校验错误落到 500 的缺口；本分支已映射为 `INVALID_EVENT_REQUEST` / HTTP 400，并补回归。

独立 IT 类为 `RuntimeShowcaseConfirmationOpenGaussIT`。运行时通过 Maven system properties 提供
`runtime.it.jar`、`gaussdb.it.url`、`gaussdb.it.username`、`gaussdb.it.password`；数据库必须使用专用测试库，不能指向业务数据。

自动化使用受控模型、Mate 元数据桩和独立 openGauss 测试库，浏览器使用合成 fixture；这些结果不等同于真实业务 Agent、企业 Mate 页面及生产环境联调。业务工具真实绑定、业务提示词发布和公司环境构建仍需在展示环境完成。
