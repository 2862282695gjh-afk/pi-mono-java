# Mate Tool Client 与 Session 发现设计

> 文档版本：2.1.0
>
> 状态：Implemented
>
> 更新日期：2026-08-24
> 决策记录：[ADR-0024](../decisions/0024-mate-tool-execution-credential-chain.html)、
> [ADR-0022](../decisions/0022-managed-agent-tool-system-v2.html)

## 1. 源码基线与分层

历史分析基线为 `d649866a6cae967ace18ceaeb9597edd47e5721e`；凭据链实现分析基线为
`934b5dd7d9e50b1d7359bea5f2dda71e3c4a34ac`。历史基线客户端已经具备 Agent/Skill
绑定 ID 查询、批量元数据查询和执行 RPC，但工具层仍暴露 ID/scope，并要求 Call 前先 List。
本次保留 HTTP 协议和按调用凭据解析，把模型契约改为直接按名称使用；这是产品约束和架构
改造。

| 层 | 当前类型 | 职责 |
|---|---|---|
| HTTP | `HttpMateToolClient` | 查询绑定 ID、批量元数据、恢复绑定顺序、执行 RPC |
| 契约 | `MateToolClient` | 三个方法都显式接收执行凭据快照 |
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

[PlantUML 源码](tool-system-v2/diagram.puml#L114)

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
原绑定顺序；缺失、重复或未知 ID fail closed。查询端点允许空凭据，但只要当前执行携带
凭据，Agent 查询、Skill 查询和元数据批量查询都会透传同一份快照。

Runtime 只在 `POST /sessions/{sessionId}/events` 接受时从 `X-HW-ID`、`X-HW-APPKEY` 和
`Authorization` 创建不可变 `MateCredentials`。快照通过 `RuntimeEventService`、
`RuntimeSessionEngineRegistry`、`ManagedAgentSessionRequest` 和 `AgentSessionFactory` 显式传递，
不使用 ThreadLocal、请求作用域 Bean 或进程级 resolver。共享 `HttpMateToolClient` 不保存凭据。

`ListMateTools` 和 `CallMateTool` 属于同一个 Session 工具组，使用同一份快照。Child Session
继承父执行快照；Cron 没有入站请求，使用空快照，因此 List 仍可访问免认证发现端点，Call
在任何发现或执行 HTTP 请求之前 fail closed。凭据不写数据库、Agent 目录、Prompt、消息、
事件或日志；`MateCredentials.toString()` 只显示各字段是否存在。

Runtime 不验证凭据真实性，也不因 AppKey 与 JWT 同时存在而拒绝或选择其一。执行请求只做
最低完整性检查：`X-HW-ID` 非空且 AppKey/JWT 至少一种非空；最终认证与授权由 Mate 完成。
这一实现是 CampusClaw 的架构改造，pi 没有对应 Mate 工具或凭据链。

## 5. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 2.1.0 | 2026-08-24 | 删除部署方 resolver 占位；实现 Runtime 执行级凭据快照、List/Call HTTP 透传、Child 继承和 Cron fail-closed |
| 2.0.0 | 2026-08-24 | PascalCase 双工具；按 Agent/Skill 方法拆分客户端；稳定 JSON；Session cache miss 自动完整发现且 execute 不重放 |
| 1.x | 2026-08-22 以前 | 历史 ID/scope List 与 Call-before-List 设计，已由 ADR-0022 取代 |
