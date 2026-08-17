# Mate Tool Client 设计文档

> 模块:`coding-agent-cli`
> 分支:`mate-tool-client`(PR #140;初版 PR #136)
> 状态:Accepted
> 日期:2026-08-17(初版),2026-08-17 更新(包结构调整 + 契约提取)
> 源码基线:`e65b3826` 起,以 PR head 为准

---

## Context(为什么)

CampusClaw 需要调用 Mate 平台管理的工具(agent/skill 授权的 tool)。这批工具由 Mate 工具服务统一管理(工具元数据、授权列表、执行),且需要携带凭据(X-HW-ID + X-HW-APPKEY 或 X-HW-ID + Authorization Bearer)才能调用。

**约束**:AgentLoop / ToolExecutionPipeline / AgentTool 接口由 core 团队维护,本特性**不允许改动这三个组件**。因此需要一种纯增量方式把 Mate 工具接入现有 agent。

## 关键定义

| 名称 | 类型 | 位置 |
|---|---|---|
| `ListMateTool` | AgentTool | `tool/mate/` — 列出 agent/skill 授权的工具,刷新权限缓存 |
| `CallMateTool` | AgentTool | `tool/mate/` — 调用单个 Mate 工具,execute 内做 allow/ask/deny 决策 |
| `MateToolClient` | 接口 | `common/client/mate/` — Mate 服务契约(listTools / callTool / ToolResult) |
| `MateToolMeta` | record | `common/client/mate/` — 工具元数据:name / description / inputSchema / outputSchema / isConcurrencySafe / permission |
| `MateCredentials` | record | `common/client/mate/` — 凭据:AppKey(X-HW-ID + X-HW-APPKEY)或 JWT(X-HW-ID + Authorization Bearer) |
| `HttpMateToolClient` | 实现 | `common/client/` — 四个 protected stub(DEF-007),真实 HTTP 由内部填充 |
| `MateApprovalUI` | 接口 | `CallMateTool` 嵌套 — permission=ask 时的用户审批回调 |
| `MateToolAutoConfiguration` | 配置 | `config/` — `mate.tool.enabled` 开关(默认开)装配三个 Bean |
| `MateToolProperties` | 配置 | `config/` — enabled / baseUrl / xHwId / xHwAppKey / approvalUi(fail-closed 默认) |

## 架构与数据流

分层与依赖方向(契约位于 `common/client/mate`,`HttpMateToolClient` 不反向依赖工具层):

![分层图](mate-tool-client/mate_tool_client_layers.svg)

[PlantUML 源码](mate-tool-client/diagram.puml#L76)

调用时序:

![数据流](mate-tool-client/mate_tool_client_dataflow.svg)

[PlantUML 源码](mate-tool-client/diagram.puml#L1)

两图均为 `docs/designs/mate-tool-client/diagram.puml` 中的独立 `@startuml` 块,用 `plantuml -tsvg diagram.puml` 生成同名 SVG(依赖 Graphviz `dot`)。

```
模型 emit tool_use("listMateTool", {agent_id | skill_id})
  → ListMateTool.execute
    → MateToolClient.listTools(两步查询)
      1. agent/skill 元数据接口 → 授权 tool_id 列表
      2. tool 元数据接口 → MateToolMeta 列表
    → callMateTool.updateMeta(tools)   ← 刷新权限缓存
    → 返回工具列表(name + permission + description + inputSchema)给模型

模型 emit tool_use("callMateTool", {tool, args})
  → CallMateTool.execute
    → 查 metaCache[tool].permission
      deny  → 直接拒绝(MateToolExecutionException)
      ask   → MateApprovalUI.ask() → 用户 allow/deny
      allow → validateAgainstSchema(必填/类型本地校验)
            → MateToolClient.callTool(tool, args, credentials)
    → result.isError() → 抛 MateToolExecutionException(pipeline 转 isError=true)
    → 成功 → AgentToolResult(content, metadata)
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

**理由**:不改动 AgentLoop / ToolExecutionPipeline(setTools 动态注入需要改 core);tools 字段保持固定。代价:callMateTool 是元工具,由 listMateTool 返回的 inputSchema 引导模型构造 args。

### D4. 凭据构造期注入,不暴露给模型

**决策**:MateCredentials 在构造 CallMateTool 时注入(经 `MateToolProperties` 配置),execute 时传给 client;模型的 tool_use 参数只有 tool/args。

**理由**:凭据是内部传输细节,模型不可见、不可伪造。

### D5. stub 用 UnsupportedOperationException(DEF-007)

**决策**:HttpMateToolClient 的四个 Mate RPC 方法为 protected stub,抛 UnsupportedOperationException,登记 `docs/DEFERRED.md` DEF-007。

**理由**:内部 Mate HTTP 接口未定;签名与编排已冻结,内部开发只填方法体。受 `no_todo_fixme_in_delivery_code` checkstyle 规则约束,不能用 TODO 注释。

### D6. 契约类型提取到 `common/client/mate`(PR #140 评审调整)

**决策**:`MateToolClient` / `MateToolMeta` / `MateCredentials` 从 `CallMateTool` 嵌套类型提取为 `common/client/mate/` 包的顶层类型。

**理由**:初版嵌套在工具类里,导致下移到通用包的 `HttpMateToolClient` 反向依赖 `tool.mate.CallMateTool`——分层方向与"客户端下移"目标相反,客户端也无法脱离具体工具实现复用。提取后依赖方向变为:`tool/mate` → 契约 ← `common/client`,两端都只依赖契约层。`MateApprovalUI` / `MateToolExecutionException` 保留在 `CallMateTool` 内(工具层概念:审批交互与错误语义,与 client 无关)。

### D7. 目录按服务域聚合 `tool/mate/`(PR #140)

**决策**:两个工具同放 `tool/mate/`,而非初版的 `tool/call/` + `tool/list/`。

**理由**:`call`/`list` 是动作语义,未来 `CallSkillTool` / `ListSkillsTool` 等会撞名;`mate` 是服务域命名,与 `bash/read/write` 按工具、`hybrid` 按机制的维度并列。两个工具共享契约类型与 meta 缓存,放同一包内聚性最好。

## 边界情况

| 场景 | 行为 |
|---|---|
| listMateTool 无 agent_id/skill_id | 返回空列表,log warn |
| callMateTool 缓存未命中(未 listTool) | 默认 allow,直接调 client |
| ask + 非交互模式(无 ApprovalUI) | fail-closed:拒绝 |
| ask + 用户拒绝 | 抛 MateToolExecutionException,不调 client |
| client 抛异常 | callTool 包装为 isError 结果返回,不中断 agent |
| args 缺必填/类型不符 | validateAgainstSchema 本地抛 IllegalArgumentException,不达 Mate 服务 |
| Mate result.isError() | 抛 MateToolExecutionException,pipeline 转 ToolResultMessage.isError=true |
| `mate.tool.enabled=false` | 两个 AgentTool 均不注册 |

## 性能(DFX)

- metaCache 为 ConcurrentHashMap,单次 listMateTool 后同批调用零额外查询
- 四个 stub 由内部实现保证超时与重试(实现时定义)
- 无 AgentLoop 改动,对现有工具路径零开销

## 契约改动

LLM API tools 字段新增两个工具定义(listMateTool / callMateTool);Mate 服务侧契约(四个 RPC)由 DEF-007 内部定义。

## 测试

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `CallMateToolTest` | 8 | allow 放行 / ask 批准 / ask 拒绝 / deny 拒绝 / 缺参 / 凭据传递 / 缺必填参(远程未调) / 类型不符(远程未调) |
| `ListMateToolTest` | 4 | agent 过滤 / skill 过滤 / 缓存刷新生效 / 凭据共享 |
| `MateToolAutoConfigurationTest` | 4 | 默认装配两工具 / enabled=false 排除 / 配置凭据达工具 / isError 经 pipeline 传播 |
| `MateToolPropertiesTest` | 2 | approvalUi setter/getter 往返同一实例(重复字段回归) / 默认 fail-closed |

共 18 个,使用内存 `MockMateToolClient`(位于 `tool/mate/` 测试目录)。

## 验证

- `./mvnw -pl modules/coding-agent-cli -am test -Dtest='CallMateToolTest,ListMateToolTest,MateToolAutoConfigurationTest,MateToolPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false` — BUILD SUCCESS,18 tests, 0 failures
- Checkstyle: 0 violations;Spotless: clean
- `./scripts/sync-mate-campusclaw.sh` — mate-campusclaw 编译通过
- CI(PR #140 build check):pass

## 版本历史

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-08-17 | 26.0.0(初版,PR #136) | ListMateTool + CallMateTool + MateToolClient 契约 + HttpMateToolClient stub |
| 2026-08-17 | 26.0.0(PR #136 评审修复) | inputSchema 暴露与本地校验;MateToolAutoConfiguration 运行时装配;isError 经异常传播 |
| 2026-08-17 | 26.0.0(PR #140) | 目录按域聚合 `tool/mate/`;契约类型提取 `common/client/mate/`;MateToolProperties 重复字段修复 + 回归测试 |
