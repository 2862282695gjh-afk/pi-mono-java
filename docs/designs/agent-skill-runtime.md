# Agent 与 Skill 本地优先运行时设计

> 本文记录 CampusClaw 的 Agent 目录发现、CampusMate 冷启动物化、Skill 选择、本地工具快照和动态工具装配流程。

## 1. Context：背景、目标与边界

CampusMateService 保存 Agent、Skill 绑定和工具权限，CampusClaw 执行 Agent 并从本地目录加载 Skill。过去由部署脚本静态复制目录，运行时启停和实际可执行内容彼此割裂。本设计把“选定 Agent 后如何发现 Skill、按需查询工具并执行”收敛到 CampusClaw 运行时，同时保持已经确定的本地优先规则。

对应决策记录：[ADR-0007：Agent 与 Skill 运行时解析](../decisions/0007-agent-skill-runtime-resolution.html)。

CampusClaw 负责决定运行哪个 Agent。选定 `agentId` 后，CampusClaw 优先使用本地目录；只有整个 Agent 目录不存在时才调用 CampusMateService。已存在但不完整的目录按配置漂移处理并拒绝加载，避免混合新旧版本：

```text
./agent/{agentId}/.campusclaw/agentId.json
./agent/{agentId}/.campusclaw/skills/{skillName}/SKILL.md
./agent/{agentId}/.campusclaw/skills/{skillName}/skill.json
./agent/{agentId}/.campusclaw/skills/{skillName}/references/*
./agent/{agentId}/.campusclaw/skills/{skillName}/references/tools.json
./agent/{agentId}/.campusclaw/skills/{skillName}/templates/*
```

Agent 可由以下入口指定：

- CLI：`--agent-id <agentId>`
- REST：`POST /api/chat` 请求体中的 `agent_id`
- WebSocket：`/api/ws/chat?agent_id=<agentId>`

未指定 `agentId` 时保持原有非托管 Agent 行为。

## 2. 关键定义与组件职责

- **托管 Agent**：通过 `agentId` 选择、目录位于 `agents-root/{agentId}`、元数据来自 CampusMateService 的 Agent。
- **Skill 发现**：只读取 `SKILL.md` frontmatter，把 `name`、`description` 暴露给模型，不等同于激活 Skill。
- **Skill 激活**：选定 Skill 后读取本地生成的 `SKILL.md` 和已加载的工具快照，并更新下一轮 LLM 可见工具集合。
- **本地工具实现**：CampusMateService 返回工具元数据，实际执行对象始终来自当前 CampusClaw Pod 的 `ToolCatalog`。

| 组件 | 职责 |
|---|---|
| `AgentRuntimeManager` | 校验 `agentId`、本地优先加载、解析直接绑定 Skill、冷启动物化 Agent 目录、合并 Agent 系统提示词和模型配置、生成并加载 Skill 工具快照 |
| `MateServiceClient` | 调用 GetAgentRuntime 和 querySkillInfo，校验业务响应及 Agent/Skill 版本坐标 |
| `AgentSession` | 同时加载当前托管 Agent 的 Skill 和 `references/tools.json`、构建模型上下文、注册 `activate_skill`、动态更新工具集合 |
| `SessionPool` | 按 `(agentId, conversationId)` 隔离会话和持久化目录 |
| `ToolCatalog` | 提供 CampusClaw Pod 内真实可执行的 `AgentTool` 实现，并以不改写共享快照的 scoped resolve 应用各 Agent cwd 与本地工具策略 |

## 3. 架构与数据流

入口先确定 `agentId`，`SessionPool` 再调用 `AgentRuntimeManager.prepare(agentId)`。Manager 返回不可变的 `PreparedAgentRuntime`，会话以 Agent 根目录作为 `cwd`，因此现有 SkillLoader 可直接读取 `.campusclaw/skills`。LLM 只能通过顺序执行的 `activate_skill` 激活 Skill；工具激活成功后，`AgentLoop` 在下一轮从共享 AgentState 重新生成工具 schema。

会话键为 `(agentId, conversationId)`，相同 conversationId 在不同 Agent 下不会复用状态或持久化文件。
会话列表和删除接口也接受 `agent_id`，分别定位该 Agent 的持久化 cwd 和内存会话键。

## 4. 契约改动

### 4.1 获取 Agent 运行信息

```http
GET /mate-service/v1/agents/{agentId}/runtime
```

响应使用附件中的 GetAgentRuntime 字段。实现将 `bindingSkills` 同时兼容单对象和数组，因为附件定义为单对象，而运行流程需要处理 `skill-1...n`。

GetAgentRuntime 的 `bindingSkills` 只提供当前 Agent **直接绑定** Skill 的 `id`、`version` 坐标，不作为完整 Skill 定义使用。

### 4.2 获取直接绑定 Skill 的定义

```http
GET /mate-service/v1/skill/query/{skillId}
```

CampusClaw 对 GetAgentRuntime 返回的每个直接绑定 `(skillId, version)` 各调用一次 querySkillInfo。响应必须成功且唯一，并且返回的 `id`、`version` 必须与绑定坐标精确一致。CampusClaw 使用返回的 name、description、useCases、bindingTools、bindingSkills、templates 和 references 构造本地 Skill 快照。

querySkillInfo 中的 `bindingSkills` 仅作为该 Skill 的依赖元数据保存。本流程不递归查询这些依赖，不为其创建目录，也不把它们自动暴露为当前 Agent 可选择的 Skill。

### 4.3 Skill 工具本地快照

querySkillInfo 返回的 `bindingTools` 是 Skill 工具的唯一远端来源。CampusClaw 在冷启动物化 Skill 时，将全部工具写入：

```text
./agent/{agentId}/.campusclaw/skills/{skillName}/references/tools.json
```

文件格式为：

```json
{
  "tools": [
    {
      "tool_id": "xxx",
      "name": "xxx",
      "description": "xxx"
    }
  ]
}
```

`tools.json` 不按 `permission` 过滤，也不保存 `permission` 字段；每个 `bindingTools` 条目都映射为 `tool_id`、`name`、`description`。该文件必须与同目录 `skill.json` 中的全部 `bindingTools` 完全一致。本地缓存加载时会重新校验，缺失、被修改或包含额外工具都使整个 Agent Runtime fail closed。Skill 激活阶段不再调用 querySkillTools。

### 4.4 CampusClaw 入口

- CLI 新增 `--agent-id`。
- REST `POST /api/chat` 新增可选字段 `agent_id`。
- WebSocket 握手新增可选查询参数 `agent_id`。
- REST `GET/DELETE /api/conversations` 新增可选查询参数 `agent_id`。
- 新增配置前缀 `campusmate.runtime`，用于 CampusMate 地址、Agent 根目录、业务成功码和超时。

## 5. 设计决策与运行规则

### 5.1 Agent 本地发现与冷启动

1. 安全解析 `./agent/{agentId}`，拒绝路径穿越和 Agent 路径中的符号链接。
2. 同时存在规范文件 `agentId.json`、`skills/`，且目录集合与直接绑定 Skill 精确一致；每个 Skill 都有版本匹配的 `skill.json`、与元数据可重建内容完全一致的 `SKILL.md`、内容一致且无额外文件的 `references/` 与 `templates/` 时，直接加载本地缓存，不调用 CampusMateService。
3. 整个 Agent 目录不存在时调用 GetAgentRuntime，取得 Agent 信息、Agent 工具权限和直接绑定 Skill 的 `(id, version)` 列表。
4. 对每个直接绑定 Skill 调用 querySkillInfo；返回必须恰好一条且 `id/version` 与绑定坐标匹配。同一 Agent 内重复的 Skill id 或 name 均使物化失败，不能静默覆盖。
5. 使用 querySkillInfo 顶层元数据生成带 `name`、`description` frontmatter 的基础 `SKILL.md`；将 references/templates 的文本内容分别写入固定的 `references/`、`templates/` 目录，将全部 `bindingTools` 写入 `references/tools.json`，并把完整 Skill 元数据写入 `skill.json`。资源名必须是安全的单路径段，fileType 只接受 `md` 或 `txt`，拒绝路径穿越、重复文件和符号链接。
6. querySkillInfo 的 `bindingSkills` 只保存在 `skill.json`，不递归查询、不物化、不加入当前 Agent 的可见 Skill 列表。
7. 首次创建使用同一文件系统中的临时目录完整写入，再以原子目录移动发布；文件系统不支持原子目录移动时 fail closed。
8. 已存在但不完整的 Agent 目录不做原地修复，直接报告配置漂移，防止中断时形成“旧元数据 + 新 Skill”的混合版本。
9. Agent 元数据最后写入临时目录，避免把半成品误判为完整缓存。

### 5.2 Skill 发现

托管 Agent 只扫描自身的 `.campusclaw/skills`，不会加载 `~/.campusclaw/agent/skills`，防止其他 Agent 或用户级 Skill 泄漏。加载每个 `SKILL.md` 时同时加载并缓存同一 Skill 的 `references/tools.json`。初始系统提示词只包含各 Skill 的 `name`、`description`，不暴露文件位置。

### 5.3 Skill 与工具激活

初始工具集合为：

```text
baseTools = 本地工具策略 ∩ permission=allow 的 Agent 级工具
            + activate_skill
```

LLM 根据 Skill 头信息调用 `activate_skill(skillName)`。显式 `/skill:skillName` 也先执行相同的查询与激活流程。CampusClaw 随后：

1. 确认 Skill 已绑定当前 Agent、已在本地 Registry 注册，并且会话初始化时已经加载对应 `tools.json`。
2. 从会话中的 Skill 工具快照取得全部 `toolName`；激活阶段不访问 CampusMateService。
3. 将工具名与本地 `ToolCatalog` 和 CLI 工具策略求交集；任何应加载但本地不存在的工具都会使本次激活整体失败。
4. 读取本地生成的 `SKILL.md` 内容并作为 `activate_skill` 结果返回。当前接口没有真实 Skill 指令正文，因此这里只包含由元数据生成的基础内容。
5. 一次性把 Skill 工具加入 `AgentState.tools`。`AgentLoop` 在下一轮模型调用时重新生成工具 schema，因此无需改动 AgentLoop。
6. 当前 Agent turn 完成后恢复 `baseTools`。

同一条 assistant 消息不能先调用 `activate_skill`，又立即调用刚加入的工具；新工具从下一轮 LLM 调用开始可见。

## 6. 顺序图

```mermaid
sequenceDiagram
    autonumber

    participant Caller as 调用方/Agent路由
    participant Claw as CampusClaw入口
    participant Pool as SessionPool
    participant Runtime as AgentRuntimeManager
    participant Mate as CampusMateService
    participant FS as ./agent文件目录
    participant Session as AgentSession
    participant Catalog as ToolCatalog
    participant LLM as LLM

    Caller->>Claw: 用户任务 + agentId
    Claw->>Pool: getOrCreate(agentId, conversationId)
    Pool->>Runtime: prepare(agentId)
    Runtime->>FS: 检查agentId.json、skills目录和SKILL.md

    alt 本地Agent结构完整
        FS-->>Runtime: 返回本地Agent元数据和Skill文件
    else 整个Agent目录不存在
        Runtime->>Mate: GET /mate-service/v1/agents/{agentId}/runtime
        Mate-->>Runtime: Agent信息、Agent工具、直接绑定Skill id/version
        loop 每个直接绑定Skill
            Runtime->>Mate: GET /mate-service/v1/skill/query/{skillId}
            Mate-->>Runtime: Skill定义、工具权限、依赖元数据、templates/references
            Runtime->>Runtime: 校验skill id/version、name、资源文件名和fileType
        end
        Runtime->>FS: 临时目录创建.campusclaw/skills/{skillName}
        Runtime->>FS: 写入SKILL.md、skill.json、references/、templates/
        Runtime->>FS: 最后写入.campusclaw/agentId.json
        Runtime->>FS: 原子移动为./agent/{agentId}
    else 本地目录存在但不完整
        Runtime-->>Pool: 配置漂移错误（fail closed）
    end

    Runtime-->>Pool: PreparedAgentRuntime
    Pool->>Session: initialize(agentRoot, Agent元数据)
    Session->>FS: 循环读取SKILL.md和references/tools.json
    FS-->>Session: name、description和Skill工具快照
    Session->>Catalog: 解析permission=allow的Agent级工具
    Catalog-->>Session: 本地AgentTool实现
    Session->>Session: 注册Agent工具 + activate_skill
    Session->>LLM: 系统提示词、Skill名称/描述、基础工具schema

    alt LLM自主选择Skill
        LLM-->>Session: activate_skill(skillName)
    else 用户显式选择Skill
        Caller->>Session: /skill:skillName
    end

    Session->>Session: 从已加载的tools.json取得全部toolName
    Session->>Catalog: 按toolName解析本地工具并应用本地策略

    alt 工具缺失或不允许
        Catalog-->>Session: 缺失/拒绝
        Session-->>LLM: activate_skill失败且不修改工具集合
    else 激活成功
        Catalog-->>Session: Skill AgentTool实现
        Session->>FS: 读取指定Skill的本地SKILL.md
        FS-->>Session: 元数据生成的基础Skill内容
        Session->>Session: 原子更新AgentState.tools
        Session-->>LLM: 返回Skill正文和激活结果
        Note over Session,LLM: 新Skill工具从下一轮模型调用可见
        LLM-->>Session: 调用选中的Skill工具
        Session->>Catalog: 执行本地AgentTool
        Catalog-->>Session: 工具结果
        Session-->>LLM: 工具结果
        LLM-->>Session: 最终回答
    end

    Session->>Session: turn结束后恢复Agent级工具 + activate_skill
    Session-->>Caller: 返回结果
```

## 7. 边界情况与 DFX

- **安全**：`agentId`、`conversationId`、Skill name 和资源文件名都采用受限单路径段格式；资源 `fileType` 仅允许 `md|txt`；非法值、路径穿越、重复目标文件和 Agent 缓存路径中的符号链接直接拒绝。绑定 Skill 数、资源文件数、单文件和累计字节数均有上限；本地 `SKILL.md` 与资源文件必须和 `skill.json` 快照完全一致。Skill 工具元数据按 ID/名称校验，实际工具仍须同时存在于 CLI 可见范围和 Spring ToolCatalog 中。Agent 级基础工具继续应用 Agent 权限与禁止列表，Skill 级工具则按 `tools.json` 的完整列表解析；运行时缓存物化和会话持久化属于 CampusClaw 内部写入，不是 LLM 工具。
- **一致性**：冷启动先验证 GetAgentRuntime 的直接绑定坐标与每个 querySkillInfo 响应的 id/version，再在临时目录写全并原子移动；既有半成品 fail closed，不做原地拼接。激活只有在本地 Skill 内容和全部本地工具解析成功后才一次性更新工具集合。
- **并发**：同一 session 同时只允许一个 prompt 持有可变上下文。工具热重载在 turn 执行期间延迟，空闲时按各 Agent 的 cwd 分别解析；scoped resolve 不会把共享 ToolCatalog 留在另一个 Agent 的目录上下文。
- **故障隔离**：CampusMate 超时、非 2xx、业务失败码、querySkillInfo 返回零条/多条/版本不匹配、资源非法或本地工具缺失均 fail closed；失败不会发布半成品目录，也不会改变当前工具集合。
- **性能**：完整本地目录不产生 GetAgentRuntime 网络请求；Skill 激活不产生远程请求。会话复用已加载的 frontmatter 和本地工具快照。
- **可观测性**：工具重载响应包含 `deferredSessions` 和 `failedSessions`；接口异常保留操作名、HTTP/业务错误信息，目录漂移会明确失败。

## 8. 当前接口限制

当前实现严格适配已给出的接口，但契约本身还有以下限制：

- querySkillInfo 提供 references/templates 文本内容，但仍没有真实 `SKILL.md` 指令正文。冷启动只能根据 Skill 元数据生成包含 frontmatter、description 和 useCases 的基础 `SKILL.md`；若要恢复完整 Skill 工作流指令，CampusMateService 仍需补充正文或制品包。
- GetAgentRuntime 的直接绑定和 querySkillInfo 都没有明确的 Skill `enabled/status` 字段。本实现只能把 GetAgentRuntime 的直接 `bindingSkills` 视为当前有效 Skill。
- querySkillInfo 的 `bindingSkills` 只保存为依赖元数据；本流程不递归解析依赖。如果未来需要执行依赖 Skill，必须另行定义依赖的授权、可见性和版本闭包规则。
- 本地目录完整后不再请求 GetAgentRuntime，因而启停/解绑不会自动刷新本地缓存。需要后续增加 revision、TTL、显式刷新或失效推送。
- `tools.json` 是冷启动时生成的 Skill 工具元数据快照，本地缓存有效期间不会重新查询 CampusMateService；工具绑定变化仍依赖 revision、TTL、显式刷新或失效推送。
- Agent 级 `ask` 权限需要主 Agent 的用户审批协议；在该协议接入前默认拒绝。Skill 级 `permission` 不参与 `tools.json` 的生成或加载。

## 9. 代码改动点（实现映射）

本节把时序图中的每个运行时动作映射到实际代码，便于后续维护和接口演进。

| 设计动作 | 主要代码 | 实现内容 |
|---|---|---|
| 接收已选定的 Agent | `CampusClawCommand`、`ChatHandler`、`ServerMode`、`ChatWebSocketHandler` | CLI、REST、WebSocket 都传递 `agentId`；未指定时保留原有非托管 Agent 流程。 |
| 按 Agent 隔离会话 | `SessionPool` | 使用 `(agentId, conversationId)` 作为会话键；Agent 的 cwd、历史文件、删除和重命名操作均按 Agent 隔离。 |
| 本地优先准备 Runtime | `AgentRuntimeManager.prepare` | 先校验 `./agent/{agentId}` 的完整缓存；完整时不访问 CampusMate，半成品直接 fail closed。 |
| 获取 Agent 与 Skill 定义 | `MateServiceClient`、`AgentRuntimeManager` | 调用 `GetAgentRuntime` 获取 Agent 元数据和直接绑定的 `(skillId, version)`，再逐个调用 `querySkillInfo` 获取完整 Skill 快照。 |
| 物化本地目录 | `AgentRuntimeManager` | 在临时目录写入 `agentId.json`、`skill.json`、`SKILL.md`、`references/tools.json`、其他 references 和 templates，完成校验后原子发布。 |
| 校验缓存一致性 | `AgentRuntimeManager` | 校验绑定 id/version、目录集合、Skill 名称、SKILL.md 全文、tools.json、资源内容、符号链接、路径和大小限制；拒绝额外未绑定 Skill。 |
| 创建不可变运行时快照 | `PreparedAgentRuntime` | 保存 Agent 元数据、Agent 根目录和直接绑定 Skill 元数据，供单个会话使用，不让会话重新猜测绑定关系。 |
| 发现 Skill | `AgentSession`、`SkillLoader`、`SkillPromptFormatter` | 同时加载当前 Agent 的 `SKILL.md` 和 `references/tools.json`，向模型暴露 `name/description`，不暴露文件路径，也不扫描用户级 Skill。 |
| 让模型选择 Skill | `ActivateSkillTool`、`AgentSession` | 注册结构化 `activate_skill(skillName)`；显式 `/skill:name` 也进入同一激活流程。 |
| 加载 Skill 工具 | `AgentRuntimeManager`、`AgentSession`、`ToolCatalog` | 从 `references/tools.json` 加载全部 Skill 工具名，校验其与 `skill.json` 一致，再解析本地可执行工具；激活时不访问 CampusMateService。 |
| 使工具在下一轮可见 | `AgentSession`、`AgentLoop` | 激活成功后更新 `AgentState.tools`；`AgentLoop` 下一轮重新生成工具 schema，turn 结束恢复基础工具集合。 |
| 删除不需要的执行层 | `ToolCatalog`、普通 Tool Bean、部署配置 | 保留静态 Spring ToolCatalog，删除 Docker sandbox、Hybrid Tool、动态 Process Tool、DinD 部署和相关脚本；Agent 级基础工具保留禁止列表，Skill 级工具按完整 `tools.json` 加载。 |
| 保持双实现一致 | `modules/coding-agent-cli`、`mate-campusclaw` | 两套 CampusClaw 实现同步包含 Runtime Client、Runtime Manager、Session 隔离、Skill 激活和安全校验。 |

### 9.1 运行时工具集合

代码中的工具边界可以概括为：

```text
baseTools = 本地ToolCatalog
            ∩ CLI/settings工具范围
            ∩ Agent permission=allow
            - 托管Agent禁止工具
            + activate_skill

activeSkillTools = baseTools
                   + (本地ToolCatalog
                      ∩ CLI/settings工具范围
                      ∩ references/tools.json中的工具名)
```

`activate_skill` 是唯一的运行时控制工具；`tools.json` 保存全部 Skill 工具元数据，Skill 的业务工具仍必须先在本地 `ToolCatalog` 中存在。

### 9.2 与当前接口的边界

当前实现已经完成 Skill 加载、发现、激活和工具装配，但以下能力需要 CampusMateService 后续补充契约：

- `querySkillInfo` 需要提供真实 `SKILL.md` 正文或制品包，否则当前只能根据 description/useCases 生成基础正文。
- `GetAgentRuntime` 需要提供 revision、TTL 或 enabled/status，才能让本地缓存感知 Skill 启停和解绑。
- Agent/Skill Runtime 需要 revision 或失效通知，才能在 `tools.json` 生成后感知工具绑定变化。
- 如果要支持 Skill 依赖的递归加载，需要明确依赖的可见性、版本闭包和工具授权规则。

## 10. 配置

```yaml
campusmate:
  runtime:
    base-url: ${CAMPUSMATE_RUNTIME_BASE_URL:http://campusmate-service:8080}
    agents-root: ${CAMPUSCLAW_AGENTS_ROOT:agent}
    connect-timeout: PT10S
    request-timeout: PT30S
    success-code: ${CAMPUSMATE_SUCCESS_CODE:0}
```

## 11. 测试与验证范围

- GetAgentRuntime 同时兼容直接响应和 `result` 包装，`bindingSkills` 同时兼容单对象与数组，并只解释为直接绑定 Skill 的 id/version 坐标。
- 冷启动对每个直接绑定 Skill 调用一次 querySkillInfo；零条、多条、重复 id/name 或绑定版本不匹配时 fail closed。
- 完整本地目录命中时不访问 CampusMateService。
- 冷启动创建 Agent 元数据、Skill 元数据快照、基础 SKILL.md、references/tools.json，并写入 querySkillInfo 返回的 references/templates 文本文件。
- 本地存在额外未绑定 Skill、SKILL.md、tools.json 或资源遭修改、资源缺失或超出数量/大小上限时 fail closed。
- `../` 等非法 Agent ID、非法 Skill/资源名、非 `md|txt` fileType、非法 conversation ID 和缓存路径中的符号链接被拒绝。
- querySkillInfo 的依赖 `bindingSkills` 被保存但不会触发递归请求或自动成为 Agent 可见 Skill。
- 已存在但不完整的 Agent 目录 fail closed，不产生混合版本。
- tools.json 写入 querySkillInfo 返回的全部 Skill 工具，不按 `permission` 或工具名称过滤。
- 加载 Skill 时同步加载 tools.json；激活 Skill 时不调用 querySkillTools。
- 托管 Agent 不加载全局用户 Skill。
- `activate_skill` 成功后下一轮工具集合包含 Skill 工具；失败时不改变原工具集合。
- 相同 conversationId 在不同 agentId 下不会复用同一个 Session。
