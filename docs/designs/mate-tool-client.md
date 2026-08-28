# Mate Tool Client 与 Session 发现设计

> 文档版本：2.3.1
>
> 状态：Implemented
>
> 更新日期：2026-08-28
> 决策记录：[ADR-0032](../decisions/0032-tool-execution-credential-boundary.html)、
> [ADR-0024](../decisions/0024-mate-tool-execution-credential-chain.html)、
> [ADR-0022](../decisions/0022-managed-agent-tool-system-v2.html)、
> [ADR-0026](../decisions/0026-unify-campusmate-client-configuration.html)

## 1. 源码基线与分层

历史分析基线为 `d649866a6cae967ace18ceaeb9597edd47e5721e`；凭据链实现分析基线为
`934b5dd7d9e50b1d7359bea5f2dda71e3c4a34ac`。历史基线客户端已经具备 Agent/Skill
绑定 ID 查询、批量元数据查询和执行 RPC，但工具层仍暴露 ID/scope，并要求 Call 前先 List。
本轮分析的变更前实现基线为 `320d790726a70aada6100052952d5494d2a378ac`：该实现把三项
执行凭据同时发送给发现和 execute。目标实现保留按名称调用：POST Events 额外读取
`access-token`，发现请求不携带这四项值，只有工具 execute 请求携带收到的值；这是接口契约
变更与安全加固。

| 层 | 当前类型 | 职责 |
|---|---|---|
| HTTP | `HttpMateToolClient` | 查询绑定 ID、批量元数据、恢复绑定顺序、执行 RPC |
| 契约 | `MateToolClient` | 发现方法不接收凭据；`callTool` 接收 POST Events 为当前执行读取的凭据值 |
| Session | `MateToolsetFactory` / `MateToolSessionState` | 每 Session 创建工具和私有缓存 |
| 发现 | `MateToolDiscovery` / `MateToolSessionCache` | 来源快照、完整名称索引、single-flight |
| 模型工具 | `ListMateToolsTool` / `CallMateTool` | 稳定 JSON 列表和按名称调用 |

`MateToolClient` 是共享无状态客户端；`AgentTool` 和 `MateToolSessionCache` 从不作为 Spring
单例跨 Session 复用。

## 2. ListMateTools

`ListMateTools({skillName?})` 无参数查询当前 Agent，有参数时只接受当前 Agent 直接绑定 Skill
名称。每次调用实时访问 Mate，不读取列表缓存。返回 JSON 只包含：

```json
{
  "scope": {"type": "agent"},
  "tools": [{"name": "Query", "description": "...", "inputSchema": {}}]
}
```

响应不包含 Agent/Skill/tool ID、permission、outputSchema 或并发属性。成功列表会更新该来源
的名称到 ID 快照，但不清空其他来源。

## 3. CallMateTool

![缓存未命中与自动发现](tool-system-v2/mate_call_refresh.svg)

[PlantUML 源码](tool-system-v2/diagram.puml#L115)

`CallMateTool({tool,args?})` 先查当前 Session 的完整有效名称索引。miss 由
`MateToolSessionCache.resolveOrRefresh` single-flight 加载当前 Agent 和全部直接 Skill：

- 同名同 ID 合并；同名不同 ID 使整轮刷新失败；
- 所有来源成功后才原子替换完整快照；
- 并发调用共享同一轮刷新结果，包括失败；
- 刷新后仍无名称时返回 unknown；
- 实际 execute 最多发送一次，连接或业务失败均不重放。

`args` 缺省为 `{}`。模型不提交 scope、Skill 或 toolId；Mate 服务仍是最终授权和执行权威。

## 4. HTTP 契约与凭据链

Agent/Skill 查询先取得有序绑定 ID，再向元数据端点批量查询。客户端按 ID 关联结果并恢复
原绑定顺序；缺失、重复或未知 ID fail closed。`listAgentTools(agentId)`、
`listSkillTools(skillId)` 和内部元数据查询均不接收 `MateCredentials`，因此 Agent binding、
Skill binding 和 tool metadata 三类发现请求都不发送执行凭据 Header。

Runtime 只在接受 `POST /sessions/{sessionId}/events` 时读取 `X-HW-ID`、`X-HW-APPKEY`、
`Authorization` 和 `access-token`。这些值只在本次 Agent 执行及其 Child 调用期间由内存中的
`MateCredentials` 持有，经 `RuntimeEventService`、`RuntimeSessionEngineRegistry`、
`ManagedAgentSessionRequest` 和 `AgentSessionFactory` 传递；不写入数据库、Agent 目录、Event、
Prompt、模型消息或日志。执行结束后 Runtime 不再主动持有这些值。实现不使用 ThreadLocal、
请求作用域 Bean 或进程级 resolver，共享 `HttpMateToolClient` 也不保存这些值；
`MateCredentials.toString()` 只显示各字段是否存在。

Agent binding、Skill binding 和 tool metadata 查询不携带上述值。只有 `CallMateTool` 最终发送
`POST /mate-service/v1/runtime/tools/{toolId}/execute` 时，才把收到的非空值放入同名 Header；
AppKey 与 JWT 同时存在时都发送。发送 execute 前必须具有 `access-token`、`X-HW-ID` 以及
AppKey/JWT 至少一种，否则不发送 execute 请求并返回工具执行失败，但 POST Events 本身不因此
被拒绝。Runtime 不验证凭据真实性，最终认证与授权由 Mate 完成。Cron 没有入站调用方值，
因此仍可发现工具，但不能执行 Mate 工具。该行为是安全加固；pi 没有对应 Mate 工具或凭据链。

## 5. CampusMate 共享配置

自 2.2.0 起，Tool 不再使用顶层 `mate.innerGWSerive` 或私有的 `mate.endpoints`。
`HttpMateToolClient` 与 Model、受管 Runtime 共享 `campusmate.base-url`，并从
`campusmate.endpoints` 取得 Agent、Skill、Tool 元数据与 Tool execute operation。
其中 Skill query 与受管 Runtime 共用一个 `skill-info-path-template`，不再重复配置。

完整结构、源码基线、迁移规则和配置可视化见
[CampusMate 客户端共享配置设计](campusmate-shared-config.md)。本次只修改配置架构，Tool 的
HTTP method、path 和请求响应保持不变；2.3.0 只改变哪些请求会携带凭据 Header。

## 6. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 2.3.1 | 2026-08-28 | 用读取时机、内存持有期限、携带请求和缺失值行为定义工具凭据边界。 |
| 2.3.0 | 2026-08-27 | POST Events 读取 `access-token`；发现请求不携带四项值，只有 Tool execute 请求携带收到的值。 |
| 2.2.0 | 2026-08-26 | Tool 复用 CampusMate 单一 base URL、共享 Endpoint 目录与 Skill query operation；移除顶层 `mate.*` 配置。 |
| 2.1.0 | 2026-08-24 | 删除部署方 resolver 占位；POST Events 读取三项凭据并供当次 List/Call 和 Child 使用，Cron 缺少值时 fail closed |
| 2.0.0 | 2026-08-24 | PascalCase 双工具；按 Agent/Skill 方法拆分客户端；稳定 JSON；Session cache miss 自动完整发现且 execute 不重放 |
| 1.x | 2026-08-22 以前 | 历史 ID/scope List 与 Call-before-List 设计，已由 ADR-0022 取代 |
