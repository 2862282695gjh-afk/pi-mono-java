# Agent 委派静态校验（AgentBindingResolver + DelegationContext）

对应决策记录：[ADR-0012：Agent 委派静态校验与授权端口](../decisions/0012-agent-delegation-static-validation.html)。

上游设计：`mainagent-subagent-design.md` §2.3（有效子 Agent 集合）、§5.1（候选发现）、§5.2（执行前二次校验）、§5.4（可信委派上下文）。本文档描述该设计在 CampusClaw 内的第一步落地：纯函数式静态校验组件，不含任何执行逻辑。

## 1. Context

PR #138 已把 `bindingAgents`（含 `description`）与 `enabled` 完整持久化到本地快照。本 PR 在其上补齐委派发生**之前**的全部静态规则，让后续 `invoke_agent` 工具（下一 PR）只需消费校验结果：

```text
effectiveChildAgents = parentAgent.bindingAgents
                      ∩ enabledAgents
                      ∩ principalAuthorizedAgents
                      - ancestryAgents
```

## 2. 关键定义与组件职责

| 组件 | 职责 |
|---|---|
| `AgentBindingResolver` | 基于父 Agent 本地快照 `bindingAgents` 计算候选摘要（`resolve`）并在执行前对单个目标给出带原因的裁决（`validate`） |
| `DelegationContext` | §5.4 完整 13 字段可信上下文 record；深度、ancestry、自绑定等结构性不变量由 canonical constructor 强制，非法委派状态不可构造 |
| `AgentAuthorizationPolicy` | 授权端口：`(AgentPrincipal, agentId) -> boolean`；`PERMIT_ALL` 为当前缺省实现，等待租户/用户身份贯通后替换 |
| `ChildAgentMetadataSource` | 子 Agent 元数据端口：`agentId -> Optional<{version, enabled}>`；本地优先读取，不物化完整运行时 |

## 3. 架构与数据流

```text
父 Agent 快照 bindingAgents ──┐
链上 Agent id 列表（含父）────┤
principal ────────────────────┤→ AgentBindingResolver.resolve → invoke_agent 工具描述候选
子 Agent 元数据（version/enabled）┘
                                  AgentBindingResolver.validate(执行前) → Allowed | Rejected(reason)
```

校验顺序（廉价本地检查优先）：直接绑定 → 自绑定 → ancestry → 深度上限（仅 validate）→ 子元数据加载（未知即 fail closed）→ enabled → 版本钉 → 授权。

## 4. 设计决策

- **候选只来自父快照直接绑定**（§2.3）：全局 Agent 目录不可枚举，`agentId` 存在且用户有权但未绑定也拒绝（`NOT_DIRECTLY_BOUND`）。见 ADR-0012。
- **深度硬上限编码进类型**：`DelegationContext` canonical constructor 拒绝 `delegationDepth ∉ [1,2]` 且要求 `ancestry.size == depth`、`ancestry.last == parentAgentId`、`target ∉ ancestry`（同时排除自绑定）。`delegateTo` 在深度 2 上抛 `IllegalStateException`。
- **版本钉语义**：绑定声明了 `version` 时，子元数据版本必须相等，未知（null）视为不兼容（fail closed）；绑定版本留空表示不钉。
- **授权端口先行、实现后补**：`PERMIT_ALL` 缺省之下直接绑定仍是唯一安全边界；端口形状按 §2.3 固化，避免后续接入租户体系时改调用方。
- **候选摘要版本取子元数据实际值**，不取父绑定钉值——呈现给模型的是子 Agent 真实状态。

## 5. 边界情况

- 父绑定包含空/重复 `agentId`：静默跳过或去重（首个生效），`validate` 仍按精确 id 匹配。
- `ChildAgentMetadataSource` 返回空（子不存在或本地不可解析）：`UNKNOWN_CHILD`，fail closed。
- `tenantId`/`userId` 为 null：本地 CLI 场景，显式允许。
- 边缘生命周期标识（`parentAgentSessionId`/`parentRunId`/`subTaskId`/`idempotencyKey`/`deadline`）：暂允许 null，由 Dispatcher 与 SubTask 生命周期 PR 填充；结构不变量已先行锁定。

## 6. 测试

- `DelegationContextTest`（6）：入口工厂、`delegateTo` 链式推进、深度 2 硬上限、深度越界/ancestry 与父不一致/重复 ancestry/target 在 ancestry/空白身份的全部构造器拒绝路径。
- `AgentBindingResolverTest`（8）：禁用/自绑定/环/未知子过滤、中间层候选、`NOT_DIRECTLY_BOUND`/`SELF_BINDING`/`IN_ANCESTRY`/`DEPTH_EXCEEDED`/`UNKNOWN_CHILD`/`NOT_ENABLED`/`VERSION_MISMATCH`/`NOT_AUTHORIZED` 八种拒绝、允许裁决携带子实际版本、重复绑定去重。场景与本地 mock CampusMate 七 Agent 拓扑（1→2→3 两层链、4 禁用、5 自绑定、6↔7 环）一一对应。

## 7. 验证

`./mvnw -pl modules/coding-agent-cli -am test` 全量通过；mate 镜像同步后相关测试通过。本 PR 无运行时行为变化——resolver 尚无调用方，`invoke_agent` 注册与子 Agent 瞬态执行属后续 PR。
