# 前端 Slash Commands 功能设计

> 模块：`coding-agent-cli`（runtimeapi）+ `frontend/`
> 状态：**Active specification**
> 日期：2026-08-27
> 现状审查记录：[frontend-slash-commands-gap-analysis.md](frontend-slash-commands-gap-analysis.md)

---

## 1. 目标与范围

前端输入以 `/` 开头时，识别为 Slash Command 并在本地或 Runtime HTTP 执行；已识别的命令不得作为普通文本发送给模型。

首版覆盖：

- 内置：`/new`、`/resume`、`/model`、`/thinking`、`/help`、`/settings`、`/compact`
- Skill：`/skill:<skill-name> [arguments]`
- Extension：以 `<extensionId>:<command>` 命名的服务端命令

不在首版范围：运行期安装/卸载 Extension、浏览器端 Extension handler、`/compact` 取消端点、多实例 `/compact` 的自动恢复（未提供 lease/fencing 时）。

## 2. 实施依据

本文是 Slash Commands 的实施主文档，本文中的 HTTP 契约、命令行为与 `/compact` 状态机为有效实现依据。 [frontend-slash-commands-gap-analysis.md](frontend-slash-commands-gap-analysis.md) 仅记录代码现状、差距与历史复审过程，不能替代或覆盖本文。

### 2.1 开工前置条件（P0）

- 当前主仓 Runtime 源码存在未解决的 Git 冲突标记，涉及事件、投影和会话等执行链路。实现 Slash Commands 前，必须完成冲突合并，并在 JDK 21 环境通过 `./mvnw -pl :campusclaw-coding-agent -am -DskipTests compile`。未满足此条件时不得以 Slash 功能分支掩盖或绕过编译错误。
- 当前 Runtime 没有可用于会话归属校验的用户身份上下文。以下 2.2 的授权模型、数据库迁移和所有资源端点的接入必须与命令开发一起完成；`agentId`、浏览器 `callerId`、Mate 工具凭据和 `sessionId` 均不得当作用户身份或授权凭据。

### 2.2 会话授权与数据归属（P0）

新增 Runtime 的统一访问端口，所有 session 资源只能经它校验后使用：

```java
RuntimePrincipal currentPrincipal()
void requireSessionAccess(RuntimePrincipal principal, String sessionId, SessionPermission permission)
```

首版认证方案固定为 Spring Security OAuth2 Resource Server：服务校验 `Authorization: Bearer <JWT>` 的 issuer、签名、过期时间和 audience，再由 `RuntimePrincipalResolver` 从已验证的 `JwtAuthenticationToken` 构建 principal。claim 映射固定为 `sub` → `subjectId`、`campusclaw_agents`（字符串数组）→ agent scope、`campusclaw_roles`（字符串数组）→ 角色；任一必需 claim 缺失或类型错误均为未认证请求。不得信任网关注入 header、请求 body、query 参数、浏览器 `callerId` 或 Mate 凭据。首版权限为：会话创建/查询/事件/命令需要 `USE`，删除需要 `DELETE`，解除 compact 禁止态需要 `RUNTIME_OPERATIONS`。

- `t_sessions` 新增 `owner_subject_id VARCHAR(128) NOT NULL`；创建会话时写入当前 `subjectId`，之后不可由普通命令修改。
- 新增索引 `idx_t_sessions_owner_agent_updated (owner_subject_id, agent_id, updated_at DESC, id)`，用于 `/resume` 的列表和前缀查询。
- 存量迁移必须使用部署方提供的权威 owner 映射回填，再施加 `NOT NULL`；无法确认归属的历史会话必须归档并拒绝普通调用者访问，不能默认授予任意用户。
- `GET /sessions`、`GET /sessions/{id}`、`GET/POST /sessions/{id}/events`、配置接口、命令接口和 `GET /commands?sessionId=` 都在读取 session 后立即执行 `requireSessionAccess`。`agentId` 仅能缩小已授权结果集。
- 无会话 `GET /commands?scope=static` 和 `POST /commands/help` 也要求已认证调用者，但不要求某个 session 的 `USE` 权限。

### 2.3 既有 Slash 包的适配边界（P0）

现有 `command/SlashCommandRegistry`、`SlashCommandSession` 和四个 builtin handler 是非 Spring 的宿主无关基础，不会被 Runtime 自动启用。其 parser 会 trim arguments 且只识别空格分隔，不能满足本设计的参数原样保留、换行和 `hasSeparator` 语义。

- Runtime 新增 `RuntimeSlashParser` 作为唯一 parser；前端 `useSlashCommands` 以同一组测试向量实现等价语义。不得直接调用或复制 `SlashCommandRegistry.parse`。
- `WebCommandCatalog` 是 Web 命令的唯一静态目录和 `/events` 守卫来源。`/model`、`/thinking` 调用现有 Runtime 配置服务；`/compact` 调用 5.3 定义的异步服务；不得把 `SlashCommandSession.compact()` 的同步返回模型直接暴露为 Web API。
- 可复用已有 builtin 的名称、帮助文案和底层业务校验，但必须经 Web DTO、授权、错误信封和并发控制适配。`/name` 不属于本期 Web 目录。

### 2.4 会话查询与仓储契约（P0）

除 5.3 的 `/compact` 原子操作外，`RuntimeSessionRepository` 必须新增以 `subjectId` 为首要过滤条件的查询方法：

```java
Optional<RuntimeSessionDTO> findOwned(String sessionId, String subjectId)
SessionPage listAuthorized(String subjectId, String agentId, String cursor, int limit)
SessionPrefixMatches findAuthorizedByPrefix(String subjectId, String prefix, int limit)
```

Repository 只接收持久化层需要的 `subjectId`，不能依赖 HTTP 的 `RuntimePrincipal`。服务层从 `findOwned` 得到会话后继续校验 principal 的 agent scope；失败时返回与不存在相同的资源不可见错误。`listAuthorized` 使用稳定游标（`updated_at`、`id` 复合排序）；`findAuthorizedByPrefix` 最多返回 20 条 `matches` 并返回 `hasMore`。服务层不能先按 sessionId 或 prefix 查询全表、再在内存中过滤，因为那会泄露存在性，也无法保证分页稳定。

## 3. 命令目录与名称

### 3.1 静态目录与动态 Skill

`WebCommandCatalog` 只保存启动期注册的内置命令与服务端 Extension。它提供：

```java
boolean isStaticRegistered(String name)
List<CommandDescriptorDTO> staticDescriptors(boolean all)
ParsedSlashInput parseSlashInput(String text)
```

Skill 不放入静态 Catalog。带已授权 `sessionId` 的 `GET /commands` 在请求期读取该 session 所属 agent 的 runtime 目录，合并可见的 Skill descriptor；首版不得全局缓存该部分。无会话目录只返回静态命令，不泄露 Skill。

前端在 `/resume` 切换会话后必须重新拉取带 sessionId 的命令目录。

### 3.2 名称语法

```text
builtinName    := ^[a-z][a-z0-9-]{0,31}$
namespacedName := ^[a-z][a-z0-9-]{0,15}:[a-z0-9][a-z0-9-]{0,31}$
commandName    := builtinName | namespacedName
```

- `skill` 是保留 namespace；只有系统 SkillResolver 可以解析和执行 `skill:<name>`。
- Extension 必须声明合法且唯一的 `extensionId()`；其全部命令必须以 `extensionId + ':'` 开头。
- 首版 Extension 仅允许 `executionMode=SERVER`。声明 `CLIENT_LOCAL` 必须在启动期失败。

### 3.3 Command descriptor

`CommandDescriptorDTO` 与 TypeScript `SlashCommandDescriptor` 至少包含：

| 字段 | 说明 |
|---|---|
| `name` | 不带 `/` 的命令名 |
| `description` / `argsHint` | 菜单展示信息 |
| `category` | `session`、`conversation`、`system`、`skill`、`extension` |
| `executionMode` | `SERVER` 或 `CLIENT_LOCAL` |
| `requiresSession` | 是否必须存在当前会话 |
| `streaming` | 是否以 SSE 返回 |
| `sourceExtensionId` | Extension 来源，仅诊断用途 |

### 3.4 服务端命令注册契约

`WebCommandCatalog` 注册的每个服务端命令使用 `WebCommandDefinition` 描述，至少包含 `name`、`description`、`argsHint`、`category`、`requiresSession`、`streaming` 和 handler。handler 的上下文必须只暴露已验证的 `RuntimePrincipal`、已授权的可选 session 和原始 `arguments`；它不得自行重新解析 HTTP 请求或取得未经授权的 session。

- `SERVER` 命令必须有 handler；`CLIENT_LOCAL` 命令只能是内置 descriptor，handler 必须为空，后端收到其执行请求时返回稳定的 `COMMAND_CLIENT_LOCAL` 错误。
- `requiresSession=true` 的 SERVER 命令在 handler 创建前完成 2.2 的会话授权；无会话 SERVER 命令不得借用或创建隐式 session。
- `streaming=true` 的 handler 返回 SSE；在首个 SSE event 前失败时返回统一 JSON 错误信封。非流式 handler 返回 `CommandResultDTO`。

## 4. HTTP 契约

基础路径为 `/campusclaw-service/v1`。

### 4.1 查询命令目录

```text
GET /commands?sessionId={sessionId}
GET /commands?scope=static
```

- `sessionId` 形态需先做会话授权校验，再返回静态命令和该 agent 可见的 Skill。
- `scope=static`（或无会话形态）只返回静态命令；`/help` 无会话场景不展示 Skill。

### 4.2 查询和恢复会话

```text
GET /sessions?agentId={agentId}&limit={limit}&cursor={cursor}
GET /sessions?sessionIdPrefix={prefix}&limit=20
```

`agentId` 只能作为筛选条件，不能作为授权凭据。前缀查询返回 `matches` 与 `hasMore`：仅一条才可直接恢复；多条或 `hasMore=true` 时展示候选。

### 4.3 调用命令

有会话命令：

```text
POST /sessions/{sessionId}/commands/{name}
```

无会话服务端命令（首版主要为 `/help`）：

```text
POST /commands/{name}
```

两个端点共用请求体：

```json
{ "arguments": "原始参数串" }
```

- `arguments` 可为空；按 UTF-8 原样保留内部空白与换行，编码后最大 8 KB。
- 空 body 或缺失字段按空串处理；非字符串或超限返回 `400 ARGUMENTS_TOO_LARGE`。
- 预留 `clientRequestId` 供未来幂等重试使用，首版不赋予幂等语义。

非流式成功响应是 `CommandResultDTO`：

```json
{
  "kind": "ok",
  "output": "已完成",
  "operationId": null,
  "effects": {}
}
```

`/compact` 启动成功时返回 `operationId`；Skill 或声明 `streaming=true` 的 Extension 在同一 POST 响应中返回 `text/event-stream`。流式命令在流开始前失败时返回普通 JSON 错误信封，前端必须按 response `content-type` 分流处理。

## 5. 命令行为

| 命令 | 执行方式 | 无会话 | 关键行为 |
|---|---|---|---|
| `/new` | CLIENT_LOCAL | 可执行 | 清空当前会话视图，显示系统反馈 |
| `/resume [prefix]` | CLIENT_LOCAL | 可执行 | 查询会话、选择或恢复；恢复后刷新命令目录 |
| `/model`、`/thinking` | SERVER / 既有配置别名 | 需会话 | 查询走命令端点；写操作复用既有配置 API 和 If-Match |
| `/help` | SERVER | 可执行 | 走无会话 commands 端点，按当前目录展示可用命令 |
| `/settings` | SERVER | 需会话 | 返回会话配置摘要 |
| `/compact` | SERVER、异步持久化结果 | 需会话 | 启动确认后从事件历史查看 completed/failed/suspended |
| `/skill:<name>` | SERVER、SSE | 需会话 | 服务端解析 Skill、注入 SKILL.md 与 arguments 后提交一次 Agent prompt |
| `<extensionId>:<name>` | SERVER | 由 descriptor 决定 | 由 Extension handler 执行，可声明 streaming/requiresSession |

### 5.1 `/events` Slash 守卫

`RuntimeEventService.submit` 必须与前端复用相同的 `parseSlashInput` 语义：

- `skill:<name>`：只要名称符合 grammar，一律路由当前 session 的 `SkillResolver`；未知、不可见、禁用的 Skill 返回稳定错误，**绝不**作为普通 prompt 发送给模型。
- 静态内置/Extension 命令：`WebCommandCatalog.isStaticRegistered(name)` 命中时拒绝该 events 请求，引导使用 commands 端点。
- 其他 `/xxx`：保持普通 prompt 透传行为。

`ParsedSlashInput` 必须有 `name`、`arguments`、`hasSeparator`。菜单仅在 `!hasSeparator` 时展示；补全写入命令名和一个空格后，菜单收起并进入参数输入。

### 5.2 `/skill:<name>`

服务端按当前 session 对应 agent 的 runtime 目录解析 Skill：

- 未找到：`SKILL_NOT_FOUND`
- 当前 agent 不可见：`SKILL_NOT_VISIBLE`
- `disableModelInvocation=true`：`SKILL_INVOCATION_DISABLED`
- 解析路径不安全：`SKILL_PATH_INVALID`

读取 `SKILL.md` 后，以 Skill 内容和 `arguments` 组合为一次 user message，通过既有 submit + SSE 链路发送。Skill 正文最大 16 KB，超出时截断并在 message 中说明。前端不得“先命令 POST，再 POST /events”，避免重复执行。

#### 5.2.1 Skill resolver（P1）

新增 `RuntimeSkillResolver`，其输入为已经通过 `USE` 授权的 session、对应 agent runtime 目录和解析后的 Skill 名；输出为 descriptor 或安全读取后的 Skill 内容。它必须复用 `RuntimeAgentPromptLoader` 的 real-path、符号链接、扫描深度和受管文件大小保护，但不能复用其“过滤掉 disabled Skill 后只返回可见列表”的结果，因为命令需要区分错误原因。

- 目录查询只展示可调用 Skill；直接执行时依次稳定返回 `SKILL_NOT_FOUND`、`SKILL_NOT_VISIBLE`、`SKILL_INVOCATION_DISABLED` 或 `SKILL_PATH_INVALID`。
- resolver 在读取后再执行 16 KB prompt 注入上限；截断必须在 server 侧完成并明确写入注入 message，不依赖浏览器限制。
- `RuntimeEventService.submit` 的 `skill:<name>` 守卫和 commands 端点必须调用同一个 resolver，确保任何合法 Skill 名都不会退化为普通 prompt。

#### 5.2.2 Extension SPI（P1）

首版自定义 Extension 是**部署期**扩展，不是运行期安装。新增公开的 Spring SPI：

```java
public interface SlashCommandExtension {
    String extensionId();
    Collection<WebCommandDefinition> commands();
}
```

- 自定义扩展以依赖 jar 的 Spring Boot auto-configuration 或应用内 `@Bean` 提供；应用启动时由 `WebCommandCatalog` 收集，而不是扫描浏览器代码或任意本地目录。
- 启动期校验 extensionId、命令 grammar、`extensionId:` namespace 所有权、保留 namespace `skill`、跨内置/跨扩展重名，以及 `SERVER` executionMode。任一冲突或 `CLIENT_LOCAL` Extension 命令都使应用启动失败。
- Extension handler 与内置 SERVER 命令使用同一授权、参数 8 KB 校验、错误信封与 SSE content-type 协议；它只能从显式 `CommandExecutionContext` 获得已授权 session，不能自行读取未校验的 HTTP 参数。
- 动态安装、卸载、热加载、浏览器 handler 和不受信任 jar 隔离不属于首版；如果以后需要，必须单独定义签名、生命周期、隔离和多实例一致性方案。

### 5.3 `/compact`

`/compact` 是需要会话的服务端异步命令。它使用当前会话的历史重建 `ManagedAgentSession`，调用现有 `SessionCompactor`；它不是把 `/compact` 作为普通 prompt 发给模型，也不复用某次 `/events` 请求期的 `RuntimeEventProjector`。

#### 5.3.1 HTTP、前端与互斥

- 调用：`POST /sessions/{sessionId}/commands/compact`。首版忽略 `arguments`，请求体仍使用统一的 `CommandInvocationRequestDTO`。
- 成功启动：同步返回 JSON `{kind:'ok', output:'压缩已启动', operationId, effects:{}}`，不建立实时 SSE。前端立即在 timeline 显示启动反馈；后续通过 `GET /sessions/{sessionId}/events` 的历史分页，按 `operationId` 关联完成、失败、挂起或解除挂起的状态。
- 拒绝：会话运行中返回既有 busy 错误；已有进行中操作返回 `409 COMPACTION_IN_PROGRESS`；存在活跃禁止态返回 `409 COMPACTION_SUSPENDED`。
- 不提供浏览器侧取消端点。`/compact` 与普通 `POST /events` 必须在同一 session 上互斥；普通消息不得与“读取历史 → 压缩 → 写入终态”的过程并发，从而避免 summary 覆盖新消息。
- 禁止态清除不是 Slash Command。只有受现有运维授权保护的管理端点或运维工具可以清除；`actor` 必须由服务端安全上下文取得，`clearReason` 必填并持久化，客户端不得传入或伪造 `actor`。

#### 5.3.2 实现组件与职责

- 新增 `RuntimeCompactionCommandService`：负责命令入口、授权后的准入、会话/历史恢复、future 生命周期和结果映射。
- 新增独立的 compaction 持久化投影器：订阅本次重建 session 的 `SessionCompactionStartedEvent`、`CompletedEvent`、`FailedEvent`，将状态写入 Runtime 历史。不得依赖 `RuntimeEventProjector`，后者只服务于普通 `/events` 请求的执行期。
- `RuntimeSessionRepository` 必须提供本节定义的 session 行锁事务操作；进程内 single-flight map 只作快速路径优化，持久化状态才是重启和多实例下的事实来源。
- `RuntimeEntryCodec`、`RuntimeEventType`、事件查询和前端 history projector 必须识别本节的五种状态 entry。除了 `COMPLETED`，其余状态 entry 恢复 Agent 历史时一律返回 `null`。
- 在准入事务成功提交后，才重建 session、注册本地 operation 标记并启动 compaction future。启动或登记失败时，必须为同一 operationId 尽力写入 `FAILED`；若终态写入也失败，由后台 reconcile 处理，期间不得释放该 operation 的持久化保护。

#### 5.3.3 持久化状态机

每个压缩操作以 `operationId` 关联。它必须满足：一个 `STARTED` 至多产生一个 `COMPLETED` 或 `FAILED` 终态；`SUSPENDED` 是禁止新压缩的附加状态，不是成功终态。

| 状态 entry | payload 必含字段 | 是否进入 Agent 上下文 | 写入条件 |
|---|---|---|---|
| `SESSION_COMPACTION_STARTED` | `operationId`、`startedAt` | 否 | 准入事务内创建 |
| `SESSION_COMPACTION_COMPLETED` | `operationId`、`reason`、`keptMessages`、`removedMessages`、`completedAt` | 是，作为 summary | operation 仍 open，且 active leaf 未变化 |
| `SESSION_COMPACTION_FAILED` | `operationId`、`errorCode`、`failedAt` | 否 | operation 仍 open；不校验 leaf |
| `SESSION_COMPACTION_SUSPENDED` | `operationId`、`reason=TIMEOUT_OBSERVED`、`suspendedAt` | 否 | 超时 recovery 与 failed 在同一事务内写入 |
| `SUSPENDED_CLEARED` | `suspendedOperationId`、`actor`、`clearReason`、`clearedAt` | 否 | 只清除当前同 operationId 的活跃禁止态 |

`RuntimeEntryCodec` 对 `COMPLETED` 以外的四种 entry 必须恢复为 `null`，不得污染 Agent message。所有事件投影都必须保留 `operationId`（cleared 使用 `suspendedOperationId`）；前端只能用该标识关联状态，不能通过展示文案推断关联关系。

#### 5.3.4 准入、终态与清除的原子操作

1. `admitCompaction(sessionId, now)` 必须持有 session 行锁，并在**同一事务**内执行：
   - 存在未被 cleared 覆盖的 suspended：返回 `SUSPENDED`；
   - 存在未终态 started 且未超时：返回 `IN_PROGRESS`；
   - 存在超时 started：仅对该 operation 以 operation-open 条件写入 `FAILED(TIMEOUT_OBSERVED)` 和 `SUSPENDED`，提交后返回 `SUSPENDED`，本次请求**不得**启动新操作；
   - 否则生成 `operationId`、写入 `STARTED`，并记录 started 写入后的 `activeLeafId`，返回 `ADMITTED(operationId, expectedLeafId)`。
2. 只有事务提交后的 `afterCommit` 才能登记内存快速路径标记并启动 future。标记必须按 `operationId` compare-and-set 清理；提交失败时不得留下内存标记。
3. `tryAppendTerminalIfOpen(sessionId, operationId, entry)` 必须在 session 行锁事务内确认该 operation 的 `STARTED` 存在且尚无终态；已有终态则 no-op。`COMPLETED` 还须验证 `expectedLeafId`；leaf 冲突时，仅当 operation 仍 open 才写 `FAILED(CANCELLED_BY_NEW_MESSAGES)`。晚到的回调只能释放本地资源，不能新增第二个终态。
4. `tryClearSuspensionIfCurrent(sessionId, suspendedOperationId, actor, clearReason)` 仅在当前未清除的 suspended 属于该 operationId 时写入 `SUSPENDED_CLEARED`；已清除、operation 不匹配或已有更新 suspended 时均 no-op。准入判断的是当前活跃 suspended 配对，而不是任意历史 suspended entry。

#### 5.3.5 超时与多实例边界

- 固定超时只能证明“任务未确认完成”，不能证明旧 worker 已停止。因此首版超时后必须进入持久化禁止态，**不能**按时间自动放行。
- 单实例可在本进程确认对应 future 已终止后，以同一 operationId 清除禁止态；多实例或重启后只能由受授权运维显式清除。
- 如需多实例自动恢复，必须先实现 lease/epoch fencing：续租、原子夺取、执行前和终态前的 owner 校验。宽限期不能替代 lease/fencing。

#### 5.3.6 `/compact` 验收

- 两个并发 compact 请求只产生一个 `STARTED`，另一个返回 409；事务回滚不遗留内存标记。
- 普通 events 与 completed 竞争时，summary 不覆盖新消息，operation 以 `COMPLETED` 或 `FAILED` 之一终结。
- 超时 recovery 后旧 future 晚到不得追加第二个终态；重启后 suspended 仍返回 409；重复或晚到的 clear 不得清除新 suspended。
- 未启用 lease/fencing 的多实例下不得自动启动第二个 compact；仅在运维 clear 或满足单实例可验证终止条件后才可创建新 operation。

## 6. 前端接线

- `useSlashCommands`：加载目录、解析输入、执行本地/服务端命令、处理 effects 与 SSE。
- `CommandMenu.vue`：使用 descriptor 的 `category`、`argsHint`、`streaming` 与 `requiresSession` 渲染和禁用项。
- `App.vue`：提交入口先以原始 `draft` 调用 Slash parser，再判断 `runtime.hasSession` 或调用 `runtime.sendMessage`。仅使用 `draft.trim()` 判断空输入；不得把 trim 后的文本作为命令参数传递。这样无会话的 `/new`、`/resume`、`/help` 可执行，普通消息仍要求已有会话。
- `useSlashCommands`：用 `TextEncoder` 对 JSON 编码后的 `arguments` 做 8 KB 前置校验；补全只在 `!hasSeparator` 时展示，补全写入 `/name ` 后立即收起菜单。命令请求根据 `content-type` 分流 JSON 结果和 SSE，命令失败不调用 `sendMessage`。
- `useRuntimeApi`：复用既有 `consumeSse`；流式命令先检查 `content-type`，JSON 错误进入统一 friendlyError。

### 6.1 前后端共享错误码

以下错误码新增至 `RuntimeErrorCode`，并由 `RuntimeExceptionHandler` 统一输出既有错误信封；前端只能按 error code 决定交互，不得匹配错误文案。

| 错误码 | HTTP | 前端动作 |
|---|---:|---|
| `COMMAND_NOT_FOUND` | 404 | 作为未知命令提示，不重试 |
| `COMMAND_NOT_ROUTED` | 400 | 提示使用 commands 端点，不发送给模型 |
| `COMMAND_CLIENT_LOCAL` | 400 | 前端实现缺失，记录诊断并显示通用失败 |
| `COMMAND_REQUIRES_SESSION` | 400 | 提示先创建或恢复会话 |
| `ARGUMENTS_TOO_LARGE` | 400 | 保留输入，提示 8 KB 限制 |
| `COMPACTION_IN_PROGRESS` | 409 | 显示已有压缩进行中，刷新历史 |
| `COMPACTION_SUSPENDED` | 409 | 显示禁止态；不显示或调用运维清除入口 |
| `SKILL_NOT_FOUND`、`SKILL_NOT_VISIBLE` | 404 | 显示 Skill 不可用，不发送给模型 |
| `SKILL_INVOCATION_DISABLED`、`SKILL_PATH_INVALID` | 422 | 显示 Skill 不可调用，不发送给模型 |

没有 session 访问权时统一返回既有 `SESSION_NOT_FOUND`（404），不新增可区分的越权错误。`/compact` 启动后发生的异步失败不改变启动响应，而以 `SESSION_COMPACTION_FAILED` 历史 entry 表示。

## 7. 实施文件清单与交付顺序

本节将本文的 P0/P1 结论拆为可并行但有依赖关系的代码任务。新增类型的包名是实现约束；可以细分文件，但不得改变职责边界。

### 7.1 交付 0：恢复可构建基线（P0）

先在独立基线提交中解决当前 Runtime Java 源文件的 Git 冲突标记，保留各冲突两侧已确认的业务语义并补对应回归测试；该提交不包含 Slash 功能。使用 JDK 21 执行模块编译和现有 Runtime 测试。只有基线提交合入后，后续任务才可开始；不能通过删除功能、跳过编译或在 Slash 分支混入未审查的冲突解决来通过。

### 7.2 交付 1：授权、Owner 与会话查询（P0）

| 位置 | 修改或新增内容 |
|---|---|
| `runtimeapi/access/RuntimePrincipal.java`、`RuntimePrincipalResolver.java`、`RuntimeSessionAccessService.java` | 新增 principal、权限枚举和 session/agent scope 校验；唯一的 Web 访问授权入口。resolver 只读取已验证的 Spring Security JWT。 |
| `pom.xml`、`application.yml`、Security 配置 | 加入并配置 Spring Security OAuth2 Resource Server，配置 issuer、audience 和 JWT claim 映射；缺失或无效 Bearer token 返回 401，绝不从 `callerId`、Mate 凭据或 query 参数推导身份。 |
| `runtimeapi/dto/RuntimeSessionDTO.java`、`CreateSessionResponseVO.java` | 增加 `ownerSubjectId` 的持久化字段；对普通 API response 不暴露该字段。 |
| `RuntimeSessionRepository`、`MyBatisRuntimeSessionRepository`、`RuntimeSessionMapper` | 实现 2.4 的 owner 过滤查询、稳定游标和前缀查询；Mapper 查询条件必须包含 `owner_subject_id`。 |
| `RuntimeSessionService`、所有 session Controller、`RuntimeEventService`、配置服务 | 在读取或创建 session 的边界接入 `RuntimeSessionAccessService`；删除及运维清除使用更高权限。 |
| `db/gaussdb/install/session_schema.sql` | 新安装表增加 `owner_subject_id` 和 owner/agent/updated 复合索引。 |
| `db/gaussdb/upgrade/V<from>_to_V<to>__{schema,data,verify}.sql` | 按 upgrade README 新增升级脚本：加可回填列、分批导入权威 owner 映射、验证无 NULL、设为 `NOT NULL`、建索引。未映射记录归档后才允许收紧约束。 |

### 7.3 交付 2：命令目录、Parser 与 HTTP 骨架（P0）

| 位置 | 修改或新增内容 |
|---|---|
| `runtimeapi/command/RuntimeSlashParser.java`、`ParsedSlashInput.java` | 新增唯一解析器；仅允许首个非空白字符为 `/`，保留第一个命令名分隔符后的原始参数，提供 `name`、`arguments`、`hasSeparator`。前后端共享参数化测试向量。 |
| `runtimeapi/command/WebCommandDefinition.java`、`WebCommandCatalog.java`、`RuntimeCommandService.java` | 新增静态内置/Extension 注册、descriptor 查询、SERVER 分发与 CLIENT_LOCAL 拒绝；禁止依赖 `command/SlashCommandRegistry` 的 parser 或执行上下文。 |
| `runtimeapi/dto/CommandDescriptorDTO.java`、`CommandResultDTO.java`；`runtimeapi/vo/CommandInvocationRequestVO.java` | 定义目录、非流式结果和请求体。请求字段为可选字符串 `arguments`，缺失按空串；Jackson 类型错误或 UTF-8 JSON body 超过 8 KB 返回 `ARGUMENTS_TOO_LARGE`。 |
| `runtimeapi/web/RuntimeCommandController.java` | 实现 `GET /commands`、`POST /commands/{name}`、`POST /sessions/{sessionId}/commands/{name}`；在 service 层先做授权与 `requiresSession` 校验，再创建 handler context。 |
| `RuntimeEventService.java` | 在普通 prompt 建立之前调用 `RuntimeSlashParser` 和 `WebCommandCatalog`：静态命令拒绝，`skill:` 路由至交付 4 的 resolver，未知 `/xxx` 保持 prompt 透传。 |
| `RuntimeErrorCode.java`、`RuntimeExceptionHandler.java`、消息资源 | 注册 6.1 的错误码、状态码、国际化安全文案和 validation path 映射。 |

`ParsedSlashInput` 的解析规则为：仅忽略输入开头的空白、以及 `/` 与命令名之间的空白；命令名后的**第一个**空白字符是 separator，之后的字符串原样作为 arguments。规范用例为：`/model` → `("model", "", false)`；`/model ` → `("model", "", true)`；`/model\n a  \n` → `("model", " a  \n", true)`；`/` → `null`；非首字符 `/` → `null`。参数区的任何字节都不得 trim、折叠或标准化。

### 7.4 交付 3：`/resume`、内置命令与前端分流（P0/P1）

| 位置 | 修改或新增内容 |
|---|---|
| `RuntimeSessionController.java`、session service/VO | 提供第 4.2 的列表与 prefix API；列表默认 `limit=20`、最大 `50`，prefix API 固定最多 `20` 条并给出 `hasMore`。 |
| `frontend/src/composables/useSlashCommands.ts` | 新增目录获取、parser、补全、LOCAL/SERVER 分流、8 KB 预检、SSE/JSON content-type 分流和统一系统消息。 |
| `frontend/src/components/CommandMenu.vue` | 只在 `!hasSeparator` 时显示；仅展示当前 session 可执行的 descriptor。 |
| `frontend/src/App.vue` | 先执行 `useSlashCommands.submit(message.value)`；仅返回“非命令”时才检查 session 并调用 `runtime.sendMessage`。 |
| `frontend/src/composables/useRuntimeApi.ts` | 保留普通消息的 trim 行为，但不得处理任何已识别命令；新增 commands 请求方法和复用的 SSE consumer。 |

`/new` 和 `/resume` 只在浏览器本地执行：`/new` 清除当前会话视图但不删除服务端 session；`/resume` 无参数列出已授权会话，有 prefix 时按 `matches/hasMore` 决定直接恢复或展示候选。`/model`、`/thinking` 的写操作调用现有 If-Match 配置 API；空参数查询、`/settings` 和 `/help` 走 commands API。

### 7.5 交付 4：Skill 与 Extension（P1）

| 位置 | 修改或新增内容 |
|---|---|
| `runtimeapi/command/RuntimeSkillResolver.java` | 按 5.2.1 从已授权 session 的 agent runtime 目录枚举、验证并安全读取 Skill；为目录和执行返回同一判定结果。 |
| `runtimeapi/command/SlashCommandExtension.java` | 提供部署期 Spring SPI；`WebCommandCatalog` 在启动期收集并校验全部 Extension 命令。 |
| `RuntimeCommandService.java`、`RuntimeEventService.java` | 两条执行路径都调用同一 Skill resolver；Skill 仅发起一次既有 Agent/SSE 执行，禁止“commands 后再 events”的双提交。 |
| `frontend` command composable 与菜单 | 每次 resume 后重拉 session-aware 目录；不缓存跨 agent Skill；Skill 流失败按 6.1 显示。 |

### 7.6 交付 5：`/compact` 与历史投影（P0）

| 位置 | 修改或新增内容 |
|---|---|
| `runtimeapi/command/RuntimeCompactionCommandService.java` | 按 5.3 执行授权、`admitCompaction`、历史恢复、after-commit future 启动和本地 operation 清理。 |
| `RuntimeSessionRepository`、`MyBatisRuntimeSessionRepository`、`RuntimeSessionMapper` | 实现 `admitCompaction`、`tryAppendTerminalIfOpen`、`tryClearSuspensionIfCurrent` 和 reconcile 所需的 open-operation 查询；均在 session 行锁事务中完成。 |
| `runtimeapi/event/RuntimeCompactionHistoryProjector.java` | 新增独立投影器，订阅重建 session 的 compaction 事件并写入 5.3.3 entry；不复用 request-scoped `RuntimeEventProjector`。 |
| `RuntimeEventType.java`、`RuntimeEntryCodec.java`、`RuntimeEventQueryService.java` | 新增 suspended/cleared 类型、operationId payload、Codec 恢复规则和历史返回支持。 |
| `runtimeapi/session/CompactionReconcileWorker.java` | 扫描 started 无终态和终态写入失败的 operation；按 5.3 的禁止态规则恢复，绝不按时间自动启动替代 compact。 |
| `frontend` history projector/timeline | 按 operationId 显示 started、completed、failed、suspended、cleared；刷新或重连后状态一致。 |

## 8. 验收清单

- 主仓已清除合并冲突，并在 JDK 21 通过模块编译；Slash 改动不引入新的编译告警或绕过现有构建检查。
- 所有 session 资源以 owner subject 和 agent scope 双重校验；不同 subject 不能通过 id、前缀、列表或命令获知对方会话是否存在。owner 回填迁移和 `NOT NULL` 约束已验证。
- 前端与后端对 Slash parser 共享用例，包含空白、换行、仅 `/` 和 `hasSeparator`。
- `/resume` 覆盖授权、前缀多匹配、跨页、恢复后目录刷新。
- Skill 覆盖目录隔离、直接 `POST /events`、四类错误、SSE 及参数原样传递。
- Extension 覆盖 namespace 所有权、保留字、SERVER-only 与请求参数校验。
- `/compact` 覆盖并发准入、单终态、leaf 冲突、超时 suspended、重启、清除幂等与多实例限制。
- 无会话 `/help`、CLIENT_LOCAL 命令和 requiresSession 拒绝路径均有前后端测试。

---

代码现状、实施差距与历史复审记录见 [frontend-slash-commands-gap-analysis.md](frontend-slash-commands-gap-analysis.md)。
