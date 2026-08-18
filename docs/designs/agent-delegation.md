# Agent 委派执行链路（AgentBindingResolver + LocalAgentDispatcher + TransientAgentRunner）

对应决策记录：[ADR-0012：Agent 委派静态校验与授权端口](../decisions/0012-agent-delegation-static-validation.html)、[ADR-0014：父 Agent 调用子 Agent 的瞬态执行链路](../decisions/0014-agent-delegation-transient-execution.html)。

上游设计：`mainagent-subagent-design.md` §2.3（有效子 Agent 集合）、§5.1（候选发现）、§5.2（执行前二次校验）、§5.4（可信委派上下文）、§6（invoke_agent 工具与瞬态执行）。本文档描述两阶段落地：先纯函数式静态校验组件（已合入），再父 Agent 通过 `invoke_agent` 实际调用子 Agent 的瞬态执行链路（本 PR）。

## 1. Context

PR #138 已把 `bindingAgents`（含 `description`）与 `enabled` 完整持久化到本地快照。本 PR 在其上补齐委派发生**之前**的全部静态规则，让后续 `invoke_agent` 工具（下一 PR）只需消费校验结果：

```text
effectiveChildAgents = parentAgent.bindingAgents
                      ∩ enabledAgents
                      ∩ principalAuthorizedAgents
                      - ancestryAgents
```

## 2. 关键定义与组件职责

静态校验层（已合入）：

| 组件 | 职责 |
|---|---|
| `AgentBindingResolver` | 基于父 Agent 本地快照 `bindingAgents` 计算候选摘要（`resolve`）并在执行前对单个目标给出带原因的裁决（`validate`） |
| `DelegationContext` | §5.4 完整 13 字段可信上下文 record；深度、ancestry、自绑定等结构性不变量由 canonical constructor 强制，非法委派状态不可构造 |
| `AgentAuthorizationPolicy` | 授权端口：`(AgentPrincipal, agentId) -> boolean`；`PERMIT_ALL` 为当前缺省实现，等待租户/用户身份贯通后替换 |
| `ChildAgentMetadataSource` | 子 Agent 元数据端口：`agentId -> Optional<{version, enabled}>`；本地优先读取，不物化完整运行时 |

执行链路层（本 PR）：

| 组件 | 职责 |
|---|---|
| `InvokeAgentTool` | 无状态 `@Component` 控制工具（`ControlTool`）：`execute` 只回 ack；会话层用 `describedWith(candidates)` 生成带候选清单描述的视图 |
| `LocalAgentDispatcher` | 委派编排：候选解析（`resolveCandidates`）+ 每跳 `validate` → 构造 `DelegationContext` → `runtimeManager.prepare` → 交给 runner |
| `TransientAgentRunner` | 为每次调用创建瞬态 `AgentSession`（新 Agent/AgentState/SkillRegistry/tools，无 worker 池），`prompt(task).join()` 后取末条 AssistantMessage 文本作为子回答 |
| `DelegationState` | 入口会话携带的委派状态：dispatcher、conversationId、principal、自身 `DelegationContext`（入口为 null）与重建瞬态会话所需的 `DelegationWiring` |
| `DelegationWiring` | 重建瞬态会话的协作对象集合（aiService、modelRegistry、promptBuilder、skillLoader/expander、localTools、toolCatalog、toolSelection） |
| `LocalChildAgentMetadataSource` | `ChildAgentMetadataSource` 本地实现：`{agentsRoot}/{agentId}/.campusclaw/agentId.json` 优先，缺失时回退 `MateServiceClient` 只读查询；全部失败返回空（fail closed `UNKNOWN_CHILD`） |
| `PermitAllAgentAuthorizationPolicy` | `AgentAuthorizationPolicy` 的 `PERMIT_ALL` Spring bean 形态 |

## 3. 架构与数据流

```text
父 Agent 快照 bindingAgents ──┐
链上 Agent id 列表（含父）────┤
principal ────────────────────┤→ AgentBindingResolver.resolve → invoke_agent 工具描述候选
子 Agent 元数据（version/enabled）┘
                                  AgentBindingResolver.validate(执行前) → Allowed | Rejected(reason)
```

校验顺序（廉价本地检查优先）：直接绑定 → 自绑定 → ancestry → 深度上限（仅 validate）→ 子元数据加载（未知即 fail closed）→ enabled → 版本钉 → 授权。

执行链路（本 PR 新增，单跳全流程）：

```text
模型调用 invoke_agent(agentId, task)
  → AgentToolResult ack（无副作用）
  → AgentSession.handleAfterToolCall 识别 InvokeAgentTool.NAME
  → LocalAgentDispatcher.dispatch(state, parentRuntime, target, task, fallbackModel)
      1. resolver.validate（每跳重新校验：直接绑定/自绑定/ancestry/深度/元数据/enabled/版本/授权）
      2. Rejected → AgentRuntimeException("Agent delegation rejected: reason (detail)") → isError 工具结果
      3. DelegationContext.forEntry(...) 或 selfContext.delegateTo(target, 新 invocationId)
      4. INFO 审计日志（parent/target/ancestry/depth/invocationId/conversationId）
      5. runtimeManager.prepare(target)（validate-before-execute）
      6. DelegationState.childOf(parentState, context)
  → TransientAgentRunner.run(childRuntime, childState, task, fallbackModel)
      · sessionConfig = runtimeManager.sessionConfig(基础配置, childRuntime)（子默认模型优先）
      · 瞬态 AgentSession + setDelegationState(childState)（深度 2 的子不再暴露 invoke_agent）
      · prompt(task).join()；末条 AssistantMessage 文本即子回答（ERROR stop → 抛异常）
  → 成功：覆盖工具结果内容为子回答；失败：isError=true + 英文错误消息
```

## 4. 设计决策

- **候选只来自父快照直接绑定**（§2.3）：全局 Agent 目录不可枚举，`agentId` 存在且用户有权但未绑定也拒绝（`NOT_DIRECTLY_BOUND`）。见 ADR-0012。
- **深度硬上限编码进类型**：`DelegationContext` canonical constructor 拒绝 `delegationDepth ∉ [1,2]` 且要求 `ancestry.size == depth`、`ancestry.last == parentAgentId`、`target ∉ ancestry`（同时排除自绑定）。`delegateTo` 在深度 2 上抛 `IllegalStateException`。
- **版本钉语义**：绑定声明了 `version` 时，子元数据版本必须相等，未知（null）视为不兼容（fail closed）；绑定版本留空表示不钉。
- **授权端口先行、实现后补**：`PERMIT_ALL` 缺省之下直接绑定仍是唯一安全边界；端口形状按 §2.3 固化，避免后续接入租户体系时改调用方。
- **候选摘要版本取子元数据实际值**，不取父绑定钉值——呈现给模型的是子 Agent 真实状态。

执行链路决策（本 PR，见 ADR-0014）：

- **`invoke_agent` 是无状态控制工具**：`execute` 仅回 ack 不执行委派；真正执行由会话层 after-tool-call 钩子完成（与 `activate_skill` 同构，复用 `ControlTool` 豁免远程 allow list 的通道）。候选清单通过 `describedWith` 装饰视图写入工具描述，模型据此选目标。
- **暴露三重门**：`delegationState != null`（入口接线了 dispatcher 且会话是托管 Agent）∧ resolver 候选非空 ∧ 工具在本地 `ToolSelection` 可见。任何一条不满足就不注册 `invoke_agent`——普通（非托管）会话永远看不到它。
- **每跳重新 validate**：设计 §5.2 要求执行前二次校验；dispatcher 不信任暴露时的候选快照，`dispatch` 现场再跑一遍完整规则。`DelegationContext` 构造不变量在 validate 之后仍作为 defense in depth。
- **瞬态执行、无 worker 池**：每次调用新建 Agent/AgentState/SkillRegistry/tools，用完即弃；子 Agent 之间无共享状态，天然并发安全。子 cwd = 其 agentRoot（SkillLoader 按 cwd 找 `.campusclaw/skills`）。
- **模型回退次序**：`dispatch(fallbackModel)` 传入口会话实际解析出的模型 id；子自身 `defaultModel()` 优先，未配置才落到 fallback——由 `runtimeManager.sessionConfig` 统一处理。
- **子提示即任务**：`task` 参数作为子会话的完整用户提示；子系统提示来自其物化的 `systemPrompt.md`（sessionConfig customPrompt）。
- **审计 = 结构化 INFO 日志**：每跳一行英文日志（parent/target/ancestry/depth/invocationId/conversationId）；正式审计事件流属后续 PR（组⑤）。
- **入口接线点**：`SessionPool.createSessionWithPersistence`（server）与 `CampusClawCommand.createAgentSession`（CLI）在 `preparedRuntime != null` 且 dispatcher bean 可用时构造 `DelegationState.entry(...)`；dispatcher 缺失仅降级（不暴露工具），不报错。

## 5. 边界情况

- 父绑定包含空/重复 `agentId`：静默跳过或去重（首个生效），`validate` 仍按精确 id 匹配。
- `ChildAgentMetadataSource` 返回空（子不存在或本地不可解析）：`UNKNOWN_CHILD`，fail closed。
- `tenantId`/`userId` 为 null：本地 CLI 场景，显式允许。
- 边缘生命周期标识（`parentAgentSessionId`/`parentRunId`/`subTaskId`/`idempotencyKey`/`deadline`）：暂允许 null，由 Dispatcher 与 SubTask 生命周期 PR 填充；结构不变量已先行锁定。
- 子回答为空/无 AssistantMessage：`AgentRuntimeException`（"produced no answer"），父侧转 isError 工具结果，模型可重试或换路。
- 子 stopReason=ERROR：透传 errorMessage 到父侧错误工具结果。
- dispatcher 调用抛任意 `RuntimeException`：`handleDelegationToolCall` 捕获并转 `delegationError(...)`，父循环不中断。
- 深度 2 的子会话：`canDelegateFurther()` false → 不暴露 `invoke_agent`（模型无从发起第三跳）；即使伪造调用也会在 `resolveCandidates` 返回空后被拒。
- `conversationId`：server 入口用真实会话 id，CLI 入口 null 时降级为 "local"。

## 6. 测试

静态校验层（已合入）：

- `DelegationContextTest`（6）：入口工厂、`delegateTo` 链式推进、深度 2 硬上限、深度越界/ancestry 与父不一致/重复 ancestry/target 在 ancestry/空白身份的全部构造器拒绝路径。
- `AgentBindingResolverTest`（8）：禁用/自绑定/环/未知子过滤、中间层候选、`NOT_DIRECTLY_BOUND`/`SELF_BINDING`/`IN_ANCESTRY`/`DEPTH_EXCEEDED`/`UNKNOWN_CHILD`/`NOT_ENABLED`/`VERSION_MISMATCH`/`NOT_AUTHORIZED` 八种拒绝、允许裁决携带子实际版本、重复绑定去重。场景与本地 mock CampusMate 七 Agent 拓扑（1→2→3 两层链、4 禁用、5 自绑定、6↔7 环）一一对应。

执行链路层（本 PR）：

- `InvokeAgentToolTest`（5）：ack 内容、缺 agentId/空 task 拒绝、`describedWith` 候选枚举（含版本与空描述降级）、装饰视图执行委托不变。
- `LocalChildAgentMetadataSourceTest`（5）：本地快照优先（零远程调用）、null enabled 视为启用、无快照回退远程、本地+远程双失败 fail closed、空白 id 不查。
- `LocalAgentDispatcherTest`（6）：入口派发（子 state 深度/ancestry/conversationId 正确）、未绑定目标拒绝且不 prepare、深度上限第三跳拒绝、封顶会话候选为空、入口候选列表、空白参数拒绝。
- `TransientAgentRunnerTest`（4）：末条 AssistantMessage 文本提取、ERROR stop 透传、无回答失败、prompt future 异常包装。
- `AgentSessionTest$Delegation`（4）：有候选才暴露 invoke_agent 且描述含候选清单、无 delegationState/无候选均不暴露、委派钩子成功覆盖为子回答、拒绝转 isError。

## 7. 验证

静态校验 PR：`./mvnw -pl modules/coding-agent-cli -am test` 全量通过；本 PR 无运行时行为变化——resolver 尚无调用方。

执行链路 PR：`./mvnw -pl modules/coding-agent-cli -am test` 全量通过（新增 24 个用例）；本地 mock CampusMate（七 Agent 拓扑）e2e 验证 agent 1 → agent 2 → agent 3 两层链路可通、第三跳被拒、未绑定/禁用目标被拒。
