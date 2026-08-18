# Mate Tool Client 设计文档

> 模块:`coding-agent-cli`
> 状态:Accepted(初版 #136 / 目录调整 #140 / 内网网关对接本 PR)
> 日期:2026-08-17 初版,2026-08-18 更新(QUERYTOOLS 对接 + 简化)

---

## Context(为什么)

CampusClaw 需要调用 Mate 平台管理的工具。这批工具由 Mate 工具服务统一管理(工具元数据、执行),调用经内网网关(`mate.innerGWSerive`),listTools 查询无需凭据,callTool 需携带 agent 下发的凭据。

**约束**:AgentLoop / ToolExecutionPipeline / AgentTool 接口由 core 团队维护,本特性**不允许改动这三个组件**,采用纯增量方式接入。

## 关键定义

| 名称 | 类型 | 位置 |
|---|---|---|
| `ListMateTool` | AgentTool | `tool/mate/` — agent_id/skill_id 作为 tool ID 查元数据并返回 |
| `CallMateTool` | AgentTool | `tool/mate/` — 无状态转发工具调用,凭据经 `resolveCredentials` 钩子解析 |
| `MateToolClient` | 接口 | `common/client/mate/` — `listTools(toolIds)` / `callTool(tool, args, credentials)` |
| `MateToolMeta` | record | `common/client/mate/` — 工具元数据 |
| `MateCredentials` | record | `common/client/mate/` — 凭据(AppKey / JWT 两模式),仅 callTool 携带 |
| `HttpMateToolClient` | 实现 | `common/client/` — QUERYTOOLS 真实调用;invoke 仍为 stub(DEF-007) |
| `MateRestUtil` | 工具类 | `common/util/` — 网关 REST 调用,解 `resCode/resMsg/result` 信封 |
| `RequestHeaderInfo` | DTO | `common/dto/` — 请求头信息(内网网关无需凭据字段,`builder().build()` 即可) |
| `ToolInfo` | DTO | `common/dto/` — QUERYTOOLS 返回的 `result.data` 数组元素 |
| `MateToolAutoConfiguration` | 配置 | `config/` — 装配 + `@Value("${mate.innerGWSerive:}")` 网关地址 |

## 架构与数据流

分层与依赖方向:

![分层图](mate-tool-client/mate_tool_client_layers.svg)

[PlantUML 源码](mate-tool-client/diagram.puml#L1)

调用时序:

![数据流](mate-tool-client/mate_tool_client_dataflow.svg)

[PlantUML 源码](mate-tool-client/diagram.puml#L76)

```
listMateTool({agent_id | skill_id})
  → ListMateTool.execute (无状态)
    → MateToolClient.listTools(List.of(agentId或skillId))
      → HttpMateToolClient
        POST {mate.innerGWSerive}/mate-service/v1/runtime/tools/query   (QUERYTOOLS)
        header: RequestHeaderInfo.builder().build()      ← 无凭据
        body: {"toolIds": [...]}
        ← {"resCode":"0","resMsg":"...","result":{"data":[ToolInfo,...]}}
      → resCode != "0" 抛 IllegalStateException;result.data → List<ToolInfo>
      → toMeta() 转 MateToolMeta(name 取 toolName 兜底 toolId)
  → 返回工具列表(name + description + inputSchema)给模型

callMateTool({tool, args})
  → CallMateTool.execute (无状态)
    → resolveCredentials(tool)          ← 钩子,凭据来源由部署方实现
    → MateToolClient.callTool(tool, args, credentials)
      → invokeTool(...)                 ← DEF-007 stub,内部开发填真实调用
    → result.isError() 抛 MateToolExecutionException(pipeline 转 isError=true)
```

## 设计决策

### D1. 两个工具均无状态

**决策**:工具与 client 不保存任何会话间状态;无 metaCache、无 updateMeta。

**理由**:MateToolClient 是进程级 Spring 单例(横跨所有会话/agent),实例字段会串会话数据。每次调用自包含,结果只返回给模型。

### D2. 权限(allow/ask/deny)不在客户端执行

**决策**:客户端不做 ask 审批、不做 deny 拦截;permission 字段仅透传展示,执行交给 Mate 服务端。

**理由**:审批 UI 与权限语义暂不引入(用户决策);服务端是权限的最终裁决点。`MateApprovalUI`、metaCache、本地参数校验随权限逻辑一并移除。

### D3. listTools 免凭据,callTool 透传凭据

**决策**:`listTools(toolIds)` 不带凭据(RequestHeaderInfo 默认构造即可过网关);`callTool(tool, args, credentials)` 第三参数透传 agent 下发的 `MateCredentials`。

**理由**:内网网关的查询接口不校验凭据;工具执行需要身份。凭据来源由 `CallMateTool.resolveCredentials()` 钩子解析(每次调用执行,不缓存——进程级单例上缓存会串凭据)。

### D4. agent_id/skill_id 即 tool ID

**决策**:listMateTool 的 agent_id/skill_id 参数直接作为 QUERYTOOLS 的 tool ID 列表传入(单元素)。

**理由**:当前网关契约按 tool ID 批量查询;授权关系(agent/skill → tool)的解析由调用方或服务端完成,客户端不猜。

### D5. QUERYTOOLS 真实调用,invoke 仍为 stub

**决策**:`HttpMateToolClient.listTools` 完整实现(QUERYTOOLS);`invokeTool` 保持 `UnsupportedOperationException`(DEF-007)。

**理由**:查询接口契约已定;执行接口(路径/入参/凭据放法)待内网确认后填,签名已冻结。

### D6. 契约与工具分层(继承自 #140)

`tool/mate`(AgentTool 层)→ `common/client/mate`(契约)← `common/client`(HTTP 实现);`HttpMateToolClient` 不依赖工具层。

### D7. 网关地址经 @Value 注入

**决策**:`MateToolAutoConfiguration` 用 `@Value("${mate.innerGWSerive:}")` 读网关地址,application.yml 提供 `mate.innerGWSerive: ${mate.innerGWSerive:}` 环境变量直通。

**理由**:与仓内 `@Value` 用法一致;默认空串,未配置时调用失败于网关侧,报错清晰。

## 边界情况

| 场景 | 行为 |
|---|---|
| listMateTool 无 agent_id/skill_id | 查询空列表,返回 0 tool(s) |
| QUERYTOOLS resCode != "0" | 抛 IllegalStateException(含 resCode/resMsg) |
| QUERYTOOLS 返回空 data | 返回空工具列表 |
| callTool 网关异常 | 包装为 isError=true 的 ToolResult 返回,不中断 agent |
| Mate result.isError() | 抛 MateToolExecutionException,pipeline 转 ToolResultMessage.isError=true |
| `mate.tool.enabled=false` | 两个 AgentTool 均不注册 |
| invokeTool(stub)被调 | UnsupportedOperationException → callTool 包装为 isError 结果 |

## 性能(DFX)

- 无缓存无状态,每次调用一次网关往返
- MateRestUtil:连接超时 10s、请求超时 60s,JDK HttpClient
- 无 AgentLoop 改动,对现有工具路径零开销

## 契约改动

LLM API tools 字段新增 listMateTool / callMateTool 两个工具定义。网关契约:QUERYTOOLS(已对接);执行接口(DEF-007 内部定义)。

## 测试

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `CallMateToolTest` | 3 | 调用透传 / 未知工具错误传播 / 缺 tool 参数抛错 |
| `ListMateToolTest` | 3 | agent_id 作 tool ID / skill_id 作 tool ID / 空参查空列表 |
| `MateToolAutoConfigurationTest` | 4 | 默认装配 / enabled=false 排除 / client Bean 存在 / isError 经 pipeline 传播 |
| `MateToolPropertiesTest` | 1 | enabled 默认值 |

共 11 个,使用内存 `MockMateToolClient`。

## 验证

- `./mvnw -pl modules/coding-agent-cli -am test -Dtest='CallMateToolTest,ListMateToolTest,MateToolAutoConfigurationTest,MateToolPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false` — **11 tests, 0 failures**
- `checkstyle:check` → 0 violations;`spotless:check` → clean
- `./scripts/sync-mate-campusclaw.sh` — mate-campusclaw 编译通过

## 版本历史

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-08-17 | 26.0.0(PR #136) | 初版:双工具 + 契约 + stub,含权限审批与 metaCache |
| 2026-08-17 | 26.0.0(PR #140) | 目录按域聚合 tool/mate;契约提取 common/client/mate |
| 2026-08-18 | 26.0.0(本 PR) | QUERYTOOLS 真实对接;MateRestUtil/RequestHeaderInfo/ToolInfo;无状态化;去 ask/deny 客户端执行;凭据仅 callTool 透传;MateToolProperties 改 lombok @Data |
