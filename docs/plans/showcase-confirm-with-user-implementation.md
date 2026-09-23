# 业务确认工具与通用问答

版本：2.0.0；日期：2026-09-23。本能力作为正式业务交互维护；设计与代码仍交付 `codex/showcase-confirm-with-user`，不合入 main。

## 业务流程

园区设备告警需要值班人员补充现场情况时，业务 Agent 调用已关联的 `confirm_with_user`。前端显示原始 `label/context`、回答框和确认按钮；用户提交后，真实回答以 `{"content":"..."}` 返回原工具调用，Agent 在同一根执行中继续。

Agent 直接绑定的工具以原名调用；仅由直接 Skill 关联的工具先用 SearchTools 获取定义，再用 `CallTool({tool:"confirm_with_user",args:{label,context}})` 调用。两种入口共用可信解析、业务 Schema 校验和处理器分派。旧 CallMateTool 只作为历史名称解码，不恢复可配置入口。

执行顺序是：可信绑定解析 → 参数校验 → Host 权限和交互准入 → 本地等待 → 结果回传。无效参数保持 `isError=true`，向模型反馈字段错误与同次可信定义；不询问用户、不自动猜参或重放，公共事件隐藏内部 Schema 和敏感参数。

`label` 必须为字符串，`context` 可省略或为对象；不提取 `context.question`。保留 JSON 值与结构，包括嵌套 null、数组、空白，以及省略 context 与空对象的区别。回答沿用单个 `confirmation` 答案槽和现有规范化、长度限制。

只有 Runtime 根执行支持等待。业务确认要求当前调用入口的可信权限为 allow；ask/deny 明确拒绝，不进入第二层授权卡。Child/Cron、端口缺失或任何本地确认失败均不转为远端执行。相同名称和 ID 的重复 Agent/Skill 绑定允许合并；不同 ID、无效身份和同一授权范围内的权限冲突继续拒绝。

## 结构与生命周期

业务名称映射集中在 `BusinessToolDispatcher` 的受控注册中。`MateToolExecutor` 继续负责解析、校验和调用快照，分派器选择本地确认处理器或普通 Mate 远端处理器。适配器不依赖 Runtime 权限策略，不重复发现，也不另存一份可变 prepared 状态。

| 组件 | 职责 |
|---|---|
| `BusinessToolHandler` / `BusinessToolDispatcher` | 内部窄处理器边界；根据可信名称固定本次处理器，不提供动态插件 SDK |
| `BusinessConfirmationAdapter` | 原始业务参数转成问题快照，等待可信答案，再转换为业务 content 结果 |
| `MateRemoteToolHandler` | 普通业务工具的单次远调与原凭据透传，不自动重试 |
| `PreparedQuestionInvocation` | 扩展既有 PreparedToolInvocation，携带同一次准备的问题快照；只位于 coding-agent 层，不反向耦合 agent-core |
| `RuntimeActiveExecution` | 仅识别通用问题调用，执行权限和交互准入；不依赖具体业务适配器或业务工具名 |
| `RuntimeEventProjector` | 原子保存问题和 confirming，发布事件并关闭当前输出 |
| `RuntimePendingInteraction` | 每根最多一个等待；按精确身份交接答案，在成功、取消或异常后清理同一等待槽 |

处理器和等待端口属于当前执行，准备对象属于单次调用。只读快照固定目标、参数和处理器；答案受理仍在事务/锁内检查当前权威状态。Controller/VO 不进入业务工具，适配器不访问 Mapper。

普通 AskUserQuestion 也在 prepare 阶段解析为 PreparedQuestionInvocation，Runtime 不再重新解析工具参数。

复用 `agent.question` / `user.question_answer`，不增加 HTTP 路径、事件种类、数据库表或字段。前端 `answer_question` 是本地动作名称。Session.state 仍为 running，UI 显示等待回答；原 SSE 结束不等于执行结束。答案针对精确 question eventId，回执先行、绑定新段后再唤醒原工具，不新建根、不切模式、不批准计划，也不授权后续业务操作。

断线通过历史对账，写请求不自动重放；取消不生成成功回答。已有记录可以恢复展示，但进程崩溃后自动重建工具栈不在本能力承诺内。

## 配置与发布

业务确认无需专属功能开关。现有 `campusclaw.tools.runtime` 清单独立控制 AskUserQuestion：本分支随包 standalone YAML 和公司 properties 默认不包含它；通用 Java 缺省清单保留它。装配器不硬过滤，也不根据业务绑定自动增加或移除内置提问。

启用普通澄清时，提供完整清单并重启（列表覆盖，不是追加）：

```properties
campusclaw.tools.runtime=Read,Find,Grep,Ls,Cron,Skill,SearchTools,CallTool,Agent,ExitPlanMode,TodoUpdate,AskUserQuestion
```

AskUserQuestion 与业务确认可以并存；两者都未配置/绑定时没有问题卡入口。显式空清单仍禁用全部工具。

无新增 Maven/npm 依赖。修改 modules 后生成 campusclaw 镜像，公司 application.properties 单独维护。后端和前端配套发布；企业 Mate 页面的公共身份映射与问答事件桥接仍需独立联调。回退前结束已有等待，含 label/context 的历史不能交给只支持 questions 的旧版本，不清表回退。


## 失败诊断

工具发现与参数准备失败属于通用工具阶段，问题准入与等待失败属于交互阶段。日志使用稳定 errorCode 和 operation，附带可用的工具/Agent/Session 标识；不记录原始 label/context、答案或凭据。

| 错误类别 | 对外稳定码与日志定位 |
|---|---|
| 工具不在可信范围 | TOOL_NOT_FOUND |
| 同名身份或定义冲突 | TOOL_DEFINITION_CONFLICT |
| 元数据查询/定义缺失或绑定无效 | TOOL_DEFINITION_UNAVAILABLE；日志 resolutionReason、detail 和发现入口标识区分原因 |
| 业务 Schema 本身无效 | TOOL_DEFINITION_INVALID；查看 mate.tool.validateArguments / validateDefinition |
| 调用参数无效 | TOOL_ARGUMENTS_INVALID；模型得到字段错误和同次可信定义，公共事件只展示安全文案 |
| 入口/端口/权限/问题身份/等待冲突 | 对应 USER_QUESTION_*；查看 business.tool.confirmation 或 runtime.question/runtime.interaction 操作 |

不要求已退役的适配器初始化、二次发现错误码继续由新路径产生；旧码保留用于历史读取。公共实时与已提交历史继续投影同一稳定错误码。

公司服务器可按失败时段检索：

```bash
rg -n -C 8 'mate.tool.resolve|mate.tool.validate|business.tool.confirmation|runtime.question|runtime.interaction|USER_QUESTION_' /actual/log/directory
```

结合 toolCallId、sessionId、agentId、skillId 或 toolId 缩小范围。发现失败可以联查 listAgentTools/listSkillTools 的底层日志；新日志无法补回旧构建未记录的失败原因。

## 源码依据与验收

变更前源码基线：`6d080a5f53dcdd531c919152568f2487526349ab`，包含 main `c04bbdc49b88fb12d0e5db68be84036c05730476`。本次源码定位前缀为 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`：

- `tool/mate/{MateToolExecutor,BusinessToolDispatcher,BusinessToolHandler,MateRemoteToolHandler}.java`：可信准备和执行分派。
- `tool/question/{BusinessConfirmationAdapter,PreparedQuestionInvocation,QuestionRequestSnapshotDTO}.java`：输入输出适配与问题桥接。
- `session/AgentSessionFactory.java`、`runtimeapi/runtime/{RuntimeActiveExecution,RuntimePendingInteraction}.java`：端口装配、权限、等待与清理。
- `runtimeapi/event/{RuntimeEventProjector,RuntimeUserQuestionService,RuntimeCommittedEventFactory,CommittedEventProjection}.java`：问题提交、答案规范化及实时/历史投影。
- 仓根 `frontend/src/components/QuestionCard.vue`、`frontend/src/projectors/runtimeEventProjector.ts`：原样显示与精确关联。

pi 基线于 2026-09-23 确认默认分支 main 并 fetch `81274f0e18279035abee8c590867a8064665846a`；重读 `packages/agent/src/agent-loop.ts#prepareToolCall/#executePreparedToolCall/#runLoop` 与 `packages/ai/src/utils/validation.ts#validateToolArguments`。先校验再 hook、等待真实工具结果后续跑的结论保持；pi 校验会转换参数且 hook 参数可变，不等同于 CampusClaw 的原样业务载荷和不可变确认快照。相对此前 `d201760ffee16564aa8d9a759e0c85b70db33674` 只更新此范围，不升级其他外部基线。该快照不是稳定发布声明，未执行 pi 测试。

固定业务名、原始载荷、allow 与根入口限制属于产品约束；可信校验、不可变调用和精确答案身份属于安全加固；处理器分派与 Runtime 解耦属于架构变更。

验收覆盖两入口本地结果与零远调、普通工具远调、原始 context 变体、权限/绑定失败、参数恢复、唯一完整调用、早到答案、取消与晚到答案、下次等待复用、配置覆盖、历史及前端投影。2026-09-23 验证：30 个后端测试类共 268 项通过，0 失败、错误、跳过；前端 7 文件 192 项、类型检查和生产构建通过。前端使用 Vite configLoader runner，避免共享只读 node_modules 的配置临时文件写入限制。真实企业 Mate、公司 NativeParent 编译与 openGauss 端到端本次未重跑。

补充验证：Spotless、Checkstyle 和 Maven package（测试单独执行）通过；公司镜像已由同步脚本生成，布局测试通过。Java 机械检查未发现规范违规，记录类型和已有 Unicode 字符串的检查器边界已人工核对；测试质量扫描中两处通过 helper 完成异常码断言的提示经核对为误报。公司镜像同步使用 `--no-verify`，不代表公司依赖环境编译通过。
