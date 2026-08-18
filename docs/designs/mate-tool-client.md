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
| `ListMateTool` | AgentTool | `tool/mate/` — 传 agent_id/skill_id 列出绑定的工具 |
| `CallMateTool` | AgentTool | `tool/mate/` — 无状态转发工具调用,凭据经 `resolveCredentials` 钩子解析 |
| `MateToolClient` | 接口 | `common/client/mate/` — `listTools(agentId, skillId)` / `callTool(tool, args, credentials)` |
| `MateToolMeta` | record | `common/client/mate/` — 工具元数据 |
| `MateCredentials` | record | `common/client/mate/` — 凭据(AppKey / JWT 两模式),仅 callTool 携带 |
| `HttpMateToolClient` | 实现 | `common/client/` — QUERYTOOLS 真实调用;invoke 仍为 stub(DEF-007) |
| `MateRestUtil` | 工具类 | `common/util/` — 网关 REST 调用(executePostRawRequest / executeGetRawRequest),返回原始 body 由调用方解信封;`RequestHeaderInfo.toHeaders()` 将 15 字段映射为真实 HTTP header |
| `RequestHeaderInfo` | DTO | `common/dto/` — 请求头信息(内网网关无需凭据字段,`builder().build()` 即可) |
| `ToolInfo` | DTO | `common/dto/` — QUERYTOOLS 返回的 `result.data` 数组元素 |
| `AgentInfo` | DTO | `common/dto/` — agent 元数据,`bindingTools[].toolId` 是第一步的 tool ID 来源 |
| `QuerySkillToolsResult` / `SkillBindingTool` | DTO | `common/dto/` — skill 工具查询结果,`bindingTools[].id` 是第一步的 tool ID 来源 |
| `MateToolAutoConfiguration` | 配置 | `config/` — 装配 + `@Value("${mate.innerGWSerive:}")` 网关地址 |

## 架构与数据流

分层与依赖方向:

![分层图](mate-tool-client/mate_tool_client_layers.svg)

[PlantUML 源码](mate-tool-client/diagram.puml#L1)

调用时序:

![数据流](mate-tool-client/mate_tool_client_dataflow.svg)

[PlantUML 源码](mate-tool-client/diagram.puml#L118)

```
listMateTool({agent_id | skill_id})
  → ListMateTool.execute (无状态)
    → MateToolClient.listTools(agentId, skillId)
      → HttpMateToolClient 两步查询:
        [第一步:tool ID 来源]
        agentId: GET /mate-service/v1/agents/{agentId}
                  → result: AgentInfo → 摘 bindingTools[].toolId
        skillId: GET /mate-service/v1/skill/info/query/{skillId}
                  → result: QuerySkillToolsResult → 摘 bindingTools[].id
        (ID 列表为空 → 直接返回空工具列表,不发 QUERYTOOLS)
        [第二步:工具元数据]
        POST {mate.innerGWSerive}/mate-service/v1/runtime/tools/query   (QUERYTOOLS)
        header: RequestHeaderInfo.builder().build()      ← 无凭据
        body: {"toolIds": [第一步摘到的列表]}
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

**理由**:内网网关的查询接口不校验凭据;工具执行需要身份。凭据来源由 `CallMateTool.resolveCredentials()` 钩子解析(每次调用执行,不缓存——进程级单例上缓存会串凭据)。钩子默认 `return null`(而非空字符串凭据):空 `appKey("","")` 是"看似有凭据实际为空"的最含糊状态,DEF-007 实现后会让 invokeTool 拿到明确判据(null=未接线)。

### D4. 两步查询:先元数据摘 tool ID,再 QUERYTOOLS 查详情

**决策**:`listTools(agentId, skillId)` 先 GET agent/skill 元数据摘绑定工具 ID(agent 路径 `bindingTools[].toolId`,skill 路径 `bindingTools[].id`),再把 ID 列表 POST 给 QUERYTOOLS 查完整元数据;ID 列表为空直接返回空列表。

**理由**:授权关系(agent/skill → tool)由 Mate 元数据服务持有,客户端不自行推断;QUERYTOOLS 只按 ID 批量查详情,职责单一。三个 protected 编排方法(`queryToolIdsByAgentId`/`queryToolIdsBySkillId`/`queryToolMetaByIds`)可内网覆写。

### D5. QUERYTOOLS 真实调用,invoke 仍为 stub

**决策**:`HttpMateToolClient` 的两步查询完整实现;`invokeTool` 保持 `UnsupportedOperationException`(DEF-007)。

**理由**:查询接口契约已定;执行接口(路径/入参/凭据放法)待内网确认后填,签名已冻结。

### D6. 契约与工具分层(继承自 #140)

`tool/mate`(AgentTool 层)→ `common/client/mate`(契约)← `common/client`(HTTP 实现);`HttpMateToolClient` 不依赖工具层。

### D7. 网关地址初始化链路(环境变量注入)

**决策**:网关地址经三层链路注入,全部走标准 Spring 占位符:

```
部署机 /etc/profile                          (运维维护)
  export CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL="http://<ip>:<port>"
        │
        ▼ source /etc/profile
mate-campusclaw/scripts/install_value.sh     (mate 侧独有,已登记 sync-exclude)
  export MATE_INNERGWSERIVE="$CAMPUSINNERGWSERVICE_DOMAIN_NAME_URL"
        │
        ▼ 进程环境变量
application.yml(外网仓) / application.properties(mate 侧)
  mate.innerGWSerive=${MATE_INNERGWSERIVE:}    ← 引用全大写环境变量,默认空
        │                                     (Spring 环境派生仅识别全大写)
        ▼
MateToolAutoConfiguration
  @Value("${mate.innerGWSerive:}") → HttpMateToolClient.mateInnerGwAddress
```

**理由**:
- 占位符引用**独立环境变量** `MATE_INNERGWSERIVE` 而非属性自身——早期写法 `${mate.innerGWSerive:}` 自引用在环境变量未设置时触发 `Circular placeholder reference`,开箱启动即失败(PR #144 评审阻断项,已由 `ApplicationYmlLoadTest` 走 config-data 加载路径防回潮)
- 环境变量必须**全大写**:Spring 的属性名→环境变量派生规则(去点、全大写)只识别 `MATE_INNERGWSERIVE`,混合大小写导出不生效(二审阻断项)
- mate-campusclaw 模块加载的是 `application.properties`(非 yml),占位符条目必须写在该文件——已补 `mate.innerGWSerive=${MATE_INNERGWSERIVE:}`(mate 侧手工文件,sync 不触碰)
- 值的来源遵循 mate 侧部署惯例(与 `GAUSSDB_URL` 等同体系):运维写 `/etc/profile`,脚本 source 后导出,应用只认环境变量
- 默认空串:未配置的环境仍可启动,仅在真正调用 Mate 工具时于网关侧报错(fail-late 但报错清晰,不阻断无关功能)

## 边界情况

| 场景 | 行为 |
|---|---|
| listMateTool 无 agent_id/skill_id | 返回 0 tool(s),不发网关请求 |
| agent/skill 元数据 bindingTools 为空 | 返回 0 tool(s),不发 QUERYTOOLS |
| 任一网关调用 resCode != "0" | 抛 IllegalStateException(含 resCode/resMsg) |
| QUERYTOOLS 返回空 data | 返回空工具列表 |
| callTool 网关异常 | 包装为 isError=true 的 ToolResult 返回,不中断 agent |
| Mate result.isError() | 抛 MateToolExecutionException,pipeline 转 ToolResultMessage.isError=true |
| `mate.tool.enabled=false` | 两个 AgentTool 均不注册 |
| invokeTool(stub)被调 | UnsupportedOperationException → callTool 包装为 isError 结果 |

## 性能(DFX)

- 无缓存无状态;listTools 为两次网关往返(元数据 GET + QUERYTOOLS POST),callTool 一次
- MateRestUtil:连接超时 10s、请求超时 60s,JDK HttpClient
- 无 AgentLoop 改动,对现有工具路径零开销

## 契约改动

LLM API tools 字段新增 listMateTool / callMateTool 两个工具定义。网关契约:QUERYTOOLS(已对接);执行接口(DEF-007 内部定义)。

## 测试

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `CallMateToolTest` | 3 | 调用透传 / 未知工具错误传播 / 缺 tool 参数抛错 |
| `ListMateToolTest` | 3 | agent_id 作 tool ID / skill_id 作 tool ID / 空参查空列表 |
| `MateToolAutoConfigurationTest` | 4 | 默认装配 / enabled=false 排除 / 网关地址经属性到达 client / isError 经 pipeline 传播 |
| `MateToolPropertiesTest` | 1 | enabled 默认值 |
| `HttpMateToolClientTest` | 7 | MockWebServer 桩测试:agent 摘 toolId / skill 摘 id / 空 bindingTools 跳过 QUERYTOOLS / toolName 兜底 / 两步 resCode!=0 抛错 / 请求方法与路径 / header 发送 |
| `ApplicationYmlLoadTest` | 2 | config-data 真加载 application.yml,占位符解析无循环引用(回归) |

共 20 个:工具层使用内存 `MockMateToolClient`;HTTP 层使用 MockWebServer 桩服务(不依赖 mock client,直测 `HttpMateToolClient` 两步查询)。

## 验证

- `./mvnw -pl modules/coding-agent-cli -am test -Dtest='CallMateToolTest,ListMateToolTest,MateToolAutoConfigurationTest,MateToolPropertiesTest,ApplicationYmlLoadTest,HttpMateToolClientTest' -Dsurefire.failIfNoSpecifiedTests=false` — **20 tests, 0 failures**
- `checkstyle:check` → 0 violations;`spotless:check` → clean
- `./scripts/sync-mate-campusclaw.sh` — mate-campusclaw 编译通过

## 版本历史

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-08-17 | 26.0.0(PR #136) | 初版:双工具 + 契约 + stub,含权限审批与 metaCache |
| 2026-08-17 | 26.0.0(PR #140) | 目录按域聚合 tool/mate;契约提取 common/client/mate |
| 2026-08-18 | 26.0.0(本 PR) | QUERYTOOLS 真实对接;MateRestUtil/RequestHeaderInfo/ToolInfo;无状态化;去 ask/deny 客户端执行;凭据仅 callTool 透传;MateToolProperties 改 lombok @Data |
| 2026-08-18 | 26.0.0(本 PR 续) | listTools 两步查询:agent/skill 元数据摘 tool ID → QUERYTOOLS;新增 AgentInfo/QuerySkillToolsResult/SkillBindingTool DTO;MateRestUtil 加 GET 支持 |
| 2026-08-18 | 26.0.0(评审修复) | 占位符自引用改环境变量注入(D7 初始化链路);resolveCredentials 默认 null;MateRestUtil 删死代码、header 真发送;补 MockWebServer 桩测试与 yml 加载回归;DEF-007 收敛为仅剩 invokeTool |
