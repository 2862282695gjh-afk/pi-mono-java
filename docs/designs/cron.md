# Cron 受管 Agent 执行设计

> 文档版本：2.1.0
>
> 状态：Implemented；集群 Host 待独立设计
>
> 更新日期：2026-08-24
> 规范性工具契约：[CampusClaw 受管 Agent 工具系统 v2](tool-system-v2.md)

## 1. 源码基线与目标差异

基线 `d649866a6cae967ace18ceaeb9597edd47e5721e` 的 `CronPayload.AgentPrompt` 固化 prompt、
model、systemPrompt 和 allowedTools，`CronJobExecutor` 自行创建 Agent；`CronTool` 是全局
Spring 工具。当前实现只保存 `agentId/prompt`，并由 Runtime Session 创建 Agent 隔离的
`CronTool`。这是架构改造，当前 JSON Store 和进程内 scheduler 仍是遗留 Host 实现。

## 2. 工具契约

`Cron` 只出现在 Runtime profile，提供 `create/list/delete/trigger/status/runs`。模型不能提交
`agentId`、`model`、`system_prompt` 或 `tools`；create 自动绑定当前 Agent。所有读写动作都
先确认 Job payload 的 agentId，其他 Agent 的 Job 对当前调用者表现为 not found。

精确 JSON Schema 见 [工具系统 v2 §9](tool-system-v2.md#9-cron-边界) 和代码
`modules/cron/src/main/java/com/campusclaw/cron/tool/CronTool.java#parameters`。

## 3. 触发执行

![公共 Cron Session 装配](tool-system-v2/tool_system_architecture.svg)

[PlantUML 源码](tool-system-v2/diagram.puml#L1)

1. `CronEngine` 取得 Job 并维护运行状态；
2. `CronJobExecutor` 记录 RUNNING，将 `agentId/prompt` 交给 `CronAgentSessionRunner`；
3. `ManagedCronSessionRunner` 在触发时 `prepare(agentId)`；
4. 解析 Agent 当前 default model 和 bindingModels；
5. `AgentSessionFactory` 以 `CRON` profile 创建隔离 Session 并执行 prompt；
6. 最终 Assistant 文本写入 `CronRunLog`，异常写 FAILED。

创建 Job 时不保存模型、SYSTEM、Skill 或工具快照，因此管理面 refresh 后的新绑定会在下一次
触发自然生效。

## 4. 当前 Host 与后续边界

`CronService` 作为 Spring `SmartLifecycle` 随服务启动和停止；`CronEngine`、`CronStore` 和
`CronRunLog` 继续提供三种 schedule、进程内锁、
失败退避、自动禁用和 JSON/JSONL 持久化。它们不构成 ToB 集群调度承诺。数据库持久化、
多节点租约、misfire、幂等触发、保留策略与节点路由必须在独立 Cron Host 设计中确定。

本设计删除产品 CLI tick、OS 调度安装和 Loop 接线；对应 `tickOnce`、进程文件锁和系统调度
安装源码已删除，不存在产品或内部可达入口。

## 5. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 2.1.0 | 2026-08-24 | 删除 CLI tick、进程文件锁和 OS 调度安装遗留实现；由 Spring 服务生命周期启动当前 Cron Host |
| 2.0.0 | 2026-08-24 | Runtime-only Cron；Job 自动绑定 Agent；触发时通过公共 SessionFactory 使用 cron profile |
| 1.x | 2026-08-24 以前 | 历史 CLI/OS scheduler 与 payload 固化模型工具设计，已由 ADR-0022 取代 |
