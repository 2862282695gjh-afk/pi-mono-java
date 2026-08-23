# Mate Tool Client 与 Session 发现设计

> 文档版本：2.0.0
>
> 状态：Implemented
>
> 更新日期：2026-08-24
> 决策记录：[ADR-0021](../decisions/0021-mate-tool-credential-resolution.html)、
> [ADR-0022](../decisions/0022-managed-agent-tool-system-v2.html)

## 1. 源码基线与分层

分析基线为 `d649866a6cae967ace18ceaeb9597edd47e5721e`。基线客户端已经具备 Agent/Skill
绑定 ID 查询、批量元数据查询和执行 RPC，但工具层仍暴露 ID/scope，并要求 Call 前先 List。
本次保留 HTTP 协议和按调用凭据解析，把模型契约改为直接按名称使用；这是产品约束和架构
改造。

| 层 | 当前类型 | 职责 |
|---|---|---|
| HTTP | `HttpMateToolClient` | 查询绑定 ID、批量元数据、恢复绑定顺序、执行 RPC |
| 契约 | `MateToolClient` | `listAgentTools`、`listSkillTools`、`callTool` |
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

[PlantUML 源码](tool-system-v2/diagram.puml#L66)

`CallMateTool({tool,args?})` 先查当前 Session 的完整有效名称索引。miss 由
`MateToolSessionCache.resolveOrRefresh` single-flight 加载当前 Agent 和全部直接 Skill：

- 同名同 ID 合并；同名不同 ID 使整轮刷新失败；
- 所有来源成功后才原子替换完整快照；
- 并发调用共享同一轮刷新结果，包括失败；
- 刷新后仍无名称时返回 unknown；
- 实际 execute 最多发送一次，连接或业务失败均不重放。

`args` 缺省为 `{}`。模型不提交 scope、Skill 或 toolId；Mate 服务仍是最终授权和执行权威。

## 4. HTTP 契约与凭据

Agent/Skill 查询先取得有序绑定 ID，再向元数据端点批量查询。客户端按 ID 关联结果并恢复
原绑定顺序；缺失、重复或未知 ID fail closed。查询请求不需要工具执行凭据。

执行前由 `MateCredentialResolver` 按 tool-call 解析只读凭据快照。未注册 resolver 或凭据
不完整时，`HttpMateToolClient` 在发请求前拒绝；共享客户端不保存 Session 凭据。

## 5. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 2.0.0 | 2026-08-24 | PascalCase 双工具；按 Agent/Skill 方法拆分客户端；稳定 JSON；Session cache miss 自动完整发现且 execute 不重放 |
| 1.x | 2026-08-22 以前 | 历史 ID/scope List 与 Call-before-List 设计，已由 ADR-0022 取代 |
