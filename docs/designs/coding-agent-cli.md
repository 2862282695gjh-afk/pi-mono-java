# coding-agent-cli 模块实现设计文档

## 文档信息

| 项目 | 内容 |
|---|---|
| 模块 | `modules/coding-agent-cli` |
| 版本 | v2.0 |
| 源码基线 | `origin/main@5f4d81752acacaa219a92aa0b0b6a93427802e17` |
| 变更 | 移除 Docker Sandbox/Hybrid Tool，接入本地 ToolCatalog 与 Mate 工具客户端 |

> 本文档描述当前代码和本次清理后的目标实现。旧版中关于 Gradle、Docker Sandbox、Hybrid Tool 和个人机器路径的内容已删除。

## 1. 模块职责

`coding-agent-cli` 是 CampusClaw 的 Spring Boot/Picocli 应用装配层，依赖 `ai`、`agent-core`、`tui` 和 `cron`，负责：

- CLI 参数解析以及 interactive、one-shot、RPC、server、ACP 等模式分发。
- 组装 `AgentSession`、`SessionPool`、Skill、Extension、Slash Command 和本地工具。
- 通过 `DefaultToolCatalog` 建立本地 `AgentTool` 名称索引。
- 通过 `ListMateTool`、`CallMateTool` 和 `MateToolClient` 使用 MateService 工具管理器。
- 保留会话持久化、HTTP/WebSocket 服务、模型解析和 Agent delegation。

本模块不再负责 Docker 容器创建、沙箱路由、危险命令正则判定或 Skill 的容器内解析。

## 2. 工具架构

### 2.1 本地基础工具

以下工具由 Spring 注册并由 `SpringAgentToolSource` 收集：

| 工具 | 实现 | 说明 |
|---|---|---|
| `bash` | `BashTool` | 本地 Bash 执行，保留超时、进程清理和输出截断 |
| `read` | `ReadTool` | 本地文件读取，保留路径解析和内容截断 |
| `write` | `WriteTool` | 本地文件创建或覆盖 |
| `edit` | `EditTool` | 本地精确/模糊文本编辑 |
| `glob` | `GlobTool` | 本地路径匹配 |
| `grep` | `GrepTool` | 本地正则搜索 |
| `ls`、`edit_diff`、`loop`、`spawn_agent` | 对应本地实现 | 继续按现有 Extension/ToolCatalog 规则装配 |

这些工具不再由 `tool.execution.hybrid-enabled` 条件装配，不再暴露 `_executionMode` 参数。

### 2.2 MateService 工具

- `ListMateTool` 查询工具元数据并返回可用工具列表。
- `CallMateTool` 将工具调用参数、凭据和调用上下文转交 `MateToolClient`。
- `HttpMateToolClient` 负责 HTTP 协议适配。
- CampusClaw 不根据远端声明动态创建本地可执行 Java 类；实际本地对象仍来自 `ToolCatalog`。

完整的 Mate 客户端协议见 [`mate-tool-client.md`](mate-tool-client.md)。

## 3. Skill 与会话

`SkillLoader`、`SkillExpander` 和 `SkillManager` 只读取本地 Skill 文件；`CampusClawCommand`、`ServerMode`、`SessionPool` 不再接收沙箱解析器或沙箱开关。

托管 Agent 的本地 Skill 快照由 `AgentRuntimeManager` 准备，`AgentSession` 通过现有 `ToolCatalog` 和 Skill 激活流程提供工具。Skill 运行时设计以 [`agent-skill-runtime.md`](agent-skill-runtime.md) 为准。

## 4. 删除的公共类型和配置

以下类型不再是本模块 API：

- `ToolExecutionProperties`
- `ExecutionMode`
- `ExecutionRouter`
- `ToolExecutionStrategy`
- `DockerSandboxClient`
- `ResourceLimits`
- `SandboxResult`
- `SandboxSecurityPolicy`
- `SandboxSkillParser`
- 六个 `Hybrid*Tool`

以下配置和环境变量不再支持，部署必须删除：

- `tool.execution.*`
- `TOOL_EXECUTION_DEFAULT_MODE`
- `TOOL_EXECUTION_SANDBOX_ENABLED`
- `TOOL_EXECUTION_LOCAL_ENABLED`
- `DOCKER_HOST`
- `SKILL_SANDBOX_PARSING`

这里的 `ToolExecutionMode` 仅指 `agent-core` 中的串行/并行工具调度，名称相似但职责不同，必须保留。

## 5. 部署与安全边界

根 `Dockerfile` 使用 Maven/JDK 21 构建和 JRE 运行时，不安装 Docker CLI。Kubernetes 只部署一个应用容器，不使用 DinD、Docker socket、`privileged` 或 Docker readiness probe。

本地 `BashTool` 仍然是可执行命令面，不能描述为安全沙箱；超时、输出上限和进程清理用于可用性保护。MateService 是受管控远程工具的授权和执行边界。

## 6. 源码证据与验证

关键源码路径：

- `src/main/java/com/campusclaw/codingagent/CampusClawApplication.java`
- `src/main/java/com/campusclaw/codingagent/cli/CampusClawCommand.java`
- `src/main/java/com/campusclaw/codingagent/tool/catalog/`
- `src/main/java/com/campusclaw/codingagent/tool/mate/`
- `src/main/java/com/campusclaw/codingagent/skill/`

验证要求：

- `./mvnw -pl modules/coding-agent-cli -am test`
- `./scripts/sync-mate-campusclaw.sh` 后编译 `mate-campusclaw`
- 检查本地工具和 Mate 工具均可被 ToolCatalog/CLI 装配。
- 生产源码和资源中不得出现已删除的沙箱类型或配置键。

## 7. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v2.0 | 2026-08-19 | 删除本地 Docker Sandbox、Hybrid Tool 和 Skill Sandbox Parser，迁移至 ToolCatalog/MateService 架构 |
| v1.0 | 2026-05-14 | 基于旧实现的代码逆向设计 |
