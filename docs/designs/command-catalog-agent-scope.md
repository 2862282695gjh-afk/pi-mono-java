# 命令清单接口改为 Agent 维度

> 状态：Proposed（设计提案，待评审；本 PR 不含实现）
>
> 日期：2026-10-09
>
> 作者：TestCat
>
> 决策记录：[ADR-0104](../decisions/0104-command-catalog-agent-scope.html)
>
> 源码基线：`upstream/main @ 553dcd5e`
>
> 影响范围：runtimeapi 命令发现契约、campusclaw 前端、公司前端包装层
>
> 关联：[命令清单 HTTP 设计](command-catalog-http/) · [共享命令 HTTP](shared-command-http/) ·
> [Mate 工具凭据边界](mate-tool-client.md)

---

## 1. Context（为什么）

命令清单接口 `GET /campusclaw-service/v1/sessions/{sessionId}/commands`
（`runtimeapi/web/RuntimeCommandCatalogController`）以会话寻址。公司前端在**首次对话时才创建
会话**——用户打开页面到发出第一条消息之间不存在 sessionId，`/` 命令面板因此无法加载清单。
这是实际接入时暴露的产品缺口，不是假设场景。

分析清单的真实数据依赖：

| 依赖 | 来源 | 是否需要会话 |
|---|---|---|
| Skill 命令集合 | agent 绑定（`PreparedAgentRuntime.skills()` 的工具关联） | 否，只需 agentId |
| Builtin 命令集合 | `BuiltInToolName` 固定集 | 否 |
| 运行态可用性（SESSION_BUSY 等） | session 状态 | 是 |

而现网消费方（campusclaw 前端 `runtime/commands.ts` 的 `CommandDescriptor`）只解析
`name/kind/description/input.hint/acceptsFiles`，**未消费 `available/unavailableCode`**；
运行中的输入锁定由前端 composer 自身状态（submitting/locked）完成，不依赖清单标志。会话维度
在这个接口上没有被消费的价值。

## 2. 关键定义

| 名称 | 定义 |
|---|---|
| Agent 清单模式 | 新寻址方式：`GET /agents/{agentId}/commands`，无会话上下文 |
| 空会话语义 | Agent 模式清单 ≡ "该 agent 刚创建、IDLE、零历史的会话"的清单；纯查询，无副作用，不隐式建会话 |
| 可用性静态化 | 清单不再反映运行态；响应组装器继续过滤不可用命令（如空历史下的 compact） |
| 信任边界 | 调用方准入由公司网关（access-token）保证；Runtime 维持内网信任模型，清单不读取凭据 |

## 3. 架构与数据流

变更前：

```text
GET /sessions/{id}/commands
  → repository.find(sessionId)                # SESSION_NOT_FOUND
  → agentRuntimeManager.prepareCached(session.agentId)
  → registry.resolveComplete(session, prepared)   # 会话参与可用性判定
  → responseAssembler.assemble(...)
```

变更后：

```text
GET /agents/{agentId}/commands
  → agentRuntimeManager.prepareCached(agentId)    # AGENT_NOT_AVAILABLE
  → registry.resolveComplete(prepared)            # 无会话上下文，可用性静态默认
  → responseAssembler.assemble(...)               # 响应体与原接口同构
```

实现改动点（由后续实现 PR 承担，本 PR 不含代码）：

| 文件 | 变更 |
|---|---|
| `runtimeapi/web/RuntimeCommandCatalogController` | 路径改为 `/agents/{agentId}/commands`，入参改 agentId（复用 `ClawConstants.Agent.ID_REGEX` 校验） |
| `service/command/CommandCatalogService` | 删除 session 查找；入口改为 `listForAgent(agentId)`；失败路径对齐既有 `AGENT_NOT_AVAILABLE` |
| `service/command/CompositeCommandRegistry` | `resolveComplete` 去掉 session 入参（删除旧签名，不保留重载） |
| `service/command/SkillCommandSource` 等 Source | agentId 由 PreparedAgentRuntime 直接提供，不再经 session；可用性改为静态默认 |
| 测试 | 服务层、Controller、Source 单测跟随调整 |

## 4. 设计决策

- **D1 替换而非并存**：消费方仅两个前端（campusclaw 前端、公司前端），无第三方兼容负担；
  双寻址会引入"参数互斥"分支与两套语义。→ [ADR-0104](../decisions/0104-command-catalog-agent-scope.html)
- **D2 空会话语义 + 可用性静态化**：清单只回答"有什么命令"；运行态拦截仍由
  `POST /sessions/{id}/command` 服务端完整兜底（会话不存在、BUSY 校验不变）。
- **D3 清单不读取凭据，鉴权在公司网关**：保持"发现请求不携带凭据"的既有分层
  （见 `mate-tool-client.md` 凭据链）；access-token 仍仅在 `POST /command` 与 Events 入口读取、
  仅在 execute 透传。待确认项：公司包装层需按用户过滤可见 agentId（见 §5）。
- **D4 URL 层级与 `POST /agents/{agentId}/sessions` 一致**：agent 维度的资源挂在同一集合下，
  语义自洽。

## 5. 边界情况

| 场景 | 行为 |
|---|---|
| agentId 不存在 / 受管目录损坏 / prepare 失败 | `AGENT_NOT_AVAILABLE`（对齐现有清单服务对 prepare 失败的处理） |
| 网关只做认证、未做 agent 授权 | 任何用户可枚举任意 agent 的 Skill 能力面。本设计明示信任假设：**公司网关必须按用户过滤可见 agent；未满足前不应发布 agent 模式** |
| 运行中的会话拉清单 | 清单照常返回（无运行态标志）；执行时被 `POST /command` 拒绝 |
| 不可用命令 | 与现状一致：响应组装器过滤，不返回 |

## 6. 性能（DFX）

少一次 `t_sessions` 查询；`prepareCached` 缓存复用不变；响应体同构，无新增字段、无新增延迟。

## 7. 契约改动

- 删除：`GET /campusclaw-service/v1/sessions/{sessionId}/commands`
- 新增：`GET /campusclaw-service/v1/agents/{agentId}/commands`；响应体与原接口完全同构
  （`commands[]`：`name/kind/description/input`）
- 设计仓契约同步：本 PR 仅更新主仓设计文档；设计仓 `12-list-session-commands` 对应变更在方案
  通过后单独提交
- 前端影响：campusclaw 前端 `useRuntimeApi.listCommands` 改路径；加载时机提前——拿到
  `configuredAgentId` 即可拉取，不再等待 `createSession` 返回；公司前端包装层代理新路径

## 8. 测试

- 服务层：agent 模式解析（skill 命令来自绑定）、`AGENT_NOT_AVAILABLE`、注册表不再依赖 session
- Controller：路径参数校验、ResultBean 信封、`Cache-Control: no-store`、`Content-Language`
- 回归：`POST /command` 两个命名空间（Builtin JSON / Skill SSE）执行链路不受影响

## 9. 验证

实现 PR 需通过 `./mvnw -pl :campusclaw-coding-agent -am test` 与
`spotless:check checkstyle:check`；本文档为纯设计提案，无代码变更、无构建影响。
