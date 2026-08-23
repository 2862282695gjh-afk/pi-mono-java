# Agent 与 Skill 受管运行目录

> 文档版本：3.0.0
>
> 状态：Implemented
>
> 更新日期：2026-08-24
> 规范性工具契约：[CampusClaw 受管 Agent 工具系统 v2](tool-system-v2.md)

## 1. 源码基线

- CampusClaw：`d649866a6cae967ace18ceaeb9597edd47e5721e`
- 设计仓：`c2a495838134aa5e8bc535b906e7534b34779279`
- 实现证据：`AgentRuntimeManager#prepare/#refresh`、`PreparedAgentRuntime`、
  `FileAgentDirectoryResolver#resolve`。

基线源码观察到 CLI 物化与 HTTP 只读目录采用不同文件名，并在 Skill
`references/tools.json` 保存远端工具快照。本次把两条链收敛为服务端三入口共同使用的一个
受管目录；这是架构改造。远端工具不落盘，由 Mate 工具在 Session 内实时发现。

## 2. 目录契约

```text
agent/{agentId}/.campusclaw/
├── agent.json
├── settings.json
├── SYSTEM.md
├── agents/{agentName}.json
└── skills/{skillName}/
    ├── skill.json
    ├── SKILL.md
    ├── references/
    └── templates/
```

根 `agent.json` 是当前 Agent 身份；`agents/{agentName}.json` 是一个直接绑定 Child 的轻量
身份与固定版本；`skill.json` 保存 `schemaVersion=1`、`id`、`name`、`version`。名称必须与
路径精确一致且大小写折叠后唯一。目录拒绝符号链接和任何 `tools.json`。

## 3. prepare 与 refresh

![公共 Session 与运行目录](tool-system-v2/tool_system_architecture.svg)

[PlantUML 源码](tool-system-v2/diagram.puml#L1)

`prepare(agentId)` 先加载完整本地缓存；只有缺失或不完整时访问 Mate。远端内容先写入同级
staging 目录，通过 Agent、Skill、Child、文件类型、资源名、ID/版本和边界校验后原子发布。
发布失败清理 staging 并保留旧目录。

`refresh(agentId)` 是管理面显式操作，总是重建目录；它不由模型工具或配置热更新触发。
HTTP Session 创建、Cron 触发和 Child Execution 均调用 prepare，因此冷目录可以自动创建，
完整热目录不产生远端访问。

## 4. Session 消费

`AgentSessionFactory` 从 `PreparedAgentRuntime` 取得 SYSTEM、Skill 名称到 ID 映射、直接 Child
映射和 bindingModels，并按 `RUNTIME`、`CRON`、`CHILD_AGENT` profile 组装工具。Skill 文档
用于提示词资源，Skill ID 只在 `ListMateTools(skillName)` 与 Call miss 的完整发现中使用。

目录缓存和 Session 生命周期分离：refresh 只影响随后创建的 Session，不修改正在执行的
Session 快照。工具配置也只在应用启动时解析，不由 refresh 变更。

## 5. 安全边界

- `agentId` 必须符合领域 ID 格式且只解析为 `agents-root` 下的单目录；
- 本地缓存树任何符号链接都会使其无效；
- 资源名不允许路径分隔、`.`、`..` 或 NUL；
- Agent/Skill/Child 的名称、ID、版本和绑定坐标必须一致；
- staging 校验通过前不替换可用目录，失败不发布半成品；
- `Read`、`Find`、`Grep`、`Ls` 的边界是完整 `agent/{agentId}`，不只是 `.campusclaw`。

## 6. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 3.0.0 | 2026-08-24 | 删除 CLI 双契约和 tools.json，统一服务三入口目录、缓存优先 prepare 与管理面原子 refresh |
| 2.x | 2026-08-21 以前 | 历史 CLI 物化与 HTTP 只读目录设计，已由 ADR-0022 取代 |
