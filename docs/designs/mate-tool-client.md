# Mate Tool Client 设计文档

> 模块:`coding-agent-cli`
> 分支:`mate-tool-client`(PR #136)
> 状态:Proposed
> 日期:2026-08-17

---

## Context(为什么)

CampusClaw 需要调用 Mate 平台管理的工具(agent/skill 授权的 tool)。这批工具由 Mate 工具服务统一管理(工具元数据、授权列表、执行),且需要携带凭据(X-HW-ID + X-HW-APPKEY 或 X-HW-ID + Authorization Bearer)才能调用。

**约束**:AgentLoop / ToolExecutionPipeline / AgentTool 接口由 core 团队维护,本特性**不允许改动这三个组件**。因此需要一种纯增量方式把 Mate 工具接入现有 agent。

## 关键定义

| 名称 | 类型 | 说明 |
|---|---|---|
| `ListMateTool` | AgentTool | 列出 agent/skill 授权的工具,刷新权限缓存 |
| `CallMateTool` | AgentTool | 调用单个 Mate 工具,execute 内做 allow/ask/deny 决策 |
| `MateToolClient` | 接口 | Mate 服务抽象(listTools / callTool) |
| `HttpMateToolClient` | 实现 | 四个 protected stub(DEF-007),真实 HTTP 由内部填充 |
| `MateToolMeta` | record | 工具元数据:name / description / inputScheme / outputScheme / isConcurrencySafe / permission |
| `MateCredentials` | record | 凭据:AppKey(X-HW-ID + X-HW-APPKEY)或 JWT(X-HW-ID + Authorization Bearer) |
| `MateApprovalUI` | 接口 | permission=ask 时的用户审批回调 |

## 架构与数据流

```
模型 emit tool_use("listMateTool", {agent_id | skill_id})
  → ListMateTool.execute
    → MateToolClient.listTools(两步查询)
      1. agent/skill 元数据接口 → 授权 tool_id 列表
      2. tool 元数据接口 → MateToolMeta 列表
    → callMateTool.updateMeta(tools)   ← 刷新权限缓存
    → 返回工具列表(name + permission + description)给模型

模型 emit tool_use("callMateTool", {tool, args})
  → CallMateTool.execute
    → 查 metaCache[tool].permission
      deny  → 直接拒绝
      ask   → MateApprovalUI.ask() → 用户 allow/deny
      allow → MateToolClient.callTool(tool, args, credentials)
    → 返回工具结果
```

## 设计决策

### D1. 权限检查在 execute 内,不在 before-hook

**决策**:allow/ask/deny 判定写在 `CallMateTool.execute()` 内部。

**理由**:Mate 工具通过 `callTool` RPC 调用,**不经过 ToolExecutionPipeline**——`BeforeToolCallHandler` 只作用于 AgentTool 层,拦不到 Mate 工具。审批逻辑与工具本身同包同类,便于整体维护。

### D2. 权限跟着工具元数据走(list_tools 下发 permission)

**决策**:permission 字位由 Mate 服务在 list_tools 返回的元数据里声明,client 不维护独立规则存储。

**理由**:声明式;server 知情(工具的权限属性由 Mate 定义);与 isConcurrencySafe 同一套模式。未列出的工具默认 allow(首次调用未刷新缓存的场景)。

### D3. 两个元工具而非把 Mate 工具直接注入 tools 字段

**决策**:只新增 listMateTool + callMateTool 两个 AgentTool,Mate 工具不直接进 LLM API 的 tools 参数。

**理由**:不改动 AgentLoop / ToolExecutionPipeline(setTools 动态注入需要改 core);tools 字段保持固定。代价:callMateTool 是元工具(Mate 工具的 inputSchema 不直接暴露给 API),由 listMateTool 返回的描述引导模型。

### D4. 凭据构造期注入,不暴露给模型

**决策**:MateCredentials 在构造 CallMateTool 时注入,execute 时传给 client;模型的 tool_use 参数只有 tool/args。

**理由**:凭据是内部传输细节,模型不可见、不可伪造。

### D5. stub 用 UnsupportedOperationException(DEF-007)

**决策**:HttpMateToolClient 的四个 Mate RPC 方法为 protected stub,抛 UnsupportedOperationException,登记 `docs/DEFERRED.md` DEF-007。

**理由**:内部 Mate HTTP 接口未定;签名与编排已冻结,内部开发只填方法体。受 `no_todo_fixme_in_delivery_code` checkstyle 规则约束,不能用 TODO 注释。

## 边界情况

| 场景 | 行为 |
|---|---|
| listMateTool 无 agent_id/skill_id | 返回空列表,log warn |
| callMateTool 缓存未命中(未 listTool) | 默认 allow,直接调 client |
| ask + 非交互模式(无 ApprovalUI) | fail-closed:拒绝 |
| ask + 用户拒绝 | 返回 "User denied",不调 client |
| client 抛异常 | callTool 包装为 isError 结果返回,不中断 agent |

## 性能(DFX)

- metaCache 为 ConcurrentHashMap,单次 listMateTool 后同批调用零额外查询
- 四个 stub 由内部实现保证超时与重试(实现时定义)
- 无 AgentLoop 改动,对现有工具路径零开销

## 契约改动

LLM API tools 字段新增两个工具定义(listMateTool / callMateTool);Mate 服务侧契约(四个 RPC)由 DEF-007 内部定义。

## 测试

`CallMateToolTest`(6):allow 放行 / ask 批准 / ask 拒绝 / deny 拒绝 / 缺参 / 凭据传递
`ListMateToolTest`(4):agent 过滤 / skill 过滤 / 缓存刷新生效 / 凭据共享
共 10 个,使用内存 MockMateToolClient。

## 验证

- `./mvnw verify -pl modules/coding-agent-cli -DskipTests=false -Dtest='CallMateToolTest,ListMateToolTest'` — BUILD SUCCESS,Tests run: 10, Failures: 0
- Checkstyle: 0 violations;Spotless: clean
- CI(PR #136 build check):pass
