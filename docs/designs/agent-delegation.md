# Child Agent Execution 设计

> 文档版本：3.0.0
>
> 状态：Implemented
>
> 更新日期：2026-08-24
> 决策记录：[ADR-0022](../decisions/0022-managed-agent-tool-system-v2.html)

## 1. 结论与基线差异

CampusClaw 只公开 `Agent({agentName,task})`。基线
`d649866a6cae967ace18ceaeb9597edd47e5721e` 中的 `invoke_agent`、`spawn_agent`、
Dispatcher/Runner、ACP、HTTP 和 A2A backend 不属于目标产品入口。当前实现由
`SubagentExecutionService` 解析当前目录的直接 Child 绑定，并通过公共
`AgentSessionFactory` 创建 Child Session；这是架构改造，不是对基线行为的别名封装。

## 2. 执行边界

![公共 Session 装配](tool-system-v2/tool_system_architecture.svg)

[PlantUML 源码](tool-system-v2/diagram.puml#L1)

父子 Session 类型、工具 Pipeline、hook、错误与取消语义一致。以下状态必须隔离：

- 消息和 Assistant 历史；
- 当前 Agent 工作区和 SYSTEM；
- 工具实例与 Mate Session cache；
- Skill/Child 直接绑定映射；
- Execution 深度、祖先路径和取消域。

运行位置是部署细节，不属于 `Agent` 工具契约。

## 3. 校验顺序

1. `agentName` 精确命中 `.campusclaw/agents/{agentName}.json` 的直接绑定；
2. 拒绝自绑定、disabled 和固定版本不匹配；
3. 拒绝最大深度 1 以上的调用和祖先路径循环；
4. `prepare(childAgentId)` 并校验 Child 目录身份；
5. Child default model 优先，否则继承父模型和 thinking；
6. 最终模型必须属于 Child bindingModels。

版本不匹配不触发隐式 refresh。首版 Child profile 不公开 `Agent`，因此深度上限同时由装配
和执行校验保证。

## 4. 进度、结果和取消

`BoundAgentTool` 只向父侧投影目标、开始、工具完成和最终完成等高层进度，不泄漏 Child
推理或完整消息。父取消调用 Child `abort()`；Session 关闭时清空自身控制队列。成功结果是
最后一条非空 Assistant 文本；执行异常由公共工具 Pipeline 统一转成 `isError=true`。

## 5. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 3.0.0 | 2026-08-24 | 用 Agent 名称直接绑定和公共 SessionFactory 取代旧委派工具、特殊 after-hook 与独立 runner |
| 2.x | 2026-08-19 以前 | 历史 CLI 委派设计，已由 ADR-0022 取代 |
