# CampusClaw

CampusClaw 是基于 Java 21 和 Spring Boot 3.4.1 构建的 ToB Agent Runtime 服务。产品通过
HTTP + SSE 提供受管 Agent 会话，Runtime、Cron 和 Child Agent 共用同一套 Session 装配、
工具执行、错误和取消语义。

CampusClaw 不提供 CLI、TUI、RPC 或本地安装入口。`modules/coding-agent-cli` 是沿用的历史
模块目录名，当前产物仅作为 Spring Boot 服务启动。

## 功能概览

- **HTTP Runtime V1**：创建和管理 Agent Session，以请求范围 SSE 投影执行事件；
- **受管 Agent 目录**：从 CampusMate 准备 Agent、Skill 和直接绑定 Child 的运行目录；
- **关闭的工具集合**：仅向模型公开八个 PascalCase 内置工具，不支持动态 ToolCatalog；
- **工作区隔离**：本地文件工具只能读取当前 `agent/{agentId}`，拒绝符号链接和 realpath 越界；
- **Mate 工具**：实时发现当前 Agent/Skill 的工具，按名称解析并执行；
- **Cron 与 Child**：Cron Job 自动绑定当前 Agent，Child 只允许调用直接绑定身份；
- **多模型支持**：模型来自内置 `ModelRegistry`，凭据由部署环境提供。

## 快速开始

### 前置要求

- JDK 21；
- openGauss 或 PostgreSQL 兼容数据库；
- 至少一个受支持模型供应商的部署凭据；
- 可访问的 CampusMate 服务。

构建服务：

```bash
./mvnw -pl :campusclaw-coding-agent -am package
```

启动服务：

```bash
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar
```

默认监听 `0.0.0.0:8080`。常用部署环境变量：

| 环境变量 | 作用 |
|---|---|
| `SERVER_ADDRESS` / `SERVER_PORT` | HTTP 监听地址和端口 |
| `GAUSSDB_URL` / `GAUSSDB_USER` / `GAUSSDB_PASSWORD` | 数据库连接 |
| `GAUSSDB_SCHEMA` / `GAUSSDB_SSL_MODE` | 数据库 schema 和 SSL 模式 |
| `CAMPUSCLAW_AGENTS_ROOT` | 受管 Agent 根目录，缺省为 `agent` |
| `CAMPUSMATE_BASE_URL` | Model、受管 Runtime 与 Tool 共享的 CampusMate 服务地址（必填） |
| `CAMPUSMATE_MODEL_*` | CampusMate Model Provider 的 API、超时和 token 参数 |
| `CAMPUSMATE_RUNTIME_*` | CampusMate Runtime 客户端的超时参数 |
| `CAMPUSMATE_*_PATH*` | 六个 CampusMate HTTP operation path 或 path template |
| `CAMPUSCLAW_EVENT_CURSOR_SECRET` | Runtime 事件游标签名密钥 |

模型凭据示例：

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENAI_API_KEY="sk-..."
```

生产环境应显式配置数据库密码、稳定高熵的事件游标密钥、服务地址和 TLS 参数。

## 内置工具

公共 `BuiltInToolName` 只有以下八个名称：

| 工具 | 作用 | 执行模式 |
|---|---|---|
| `Read` | 分块读取文本或读取受支持图片 | PARALLEL |
| `Find` | 按 glob 查找文件 | PARALLEL |
| `Grep` | 按正则或字面量搜索文件内容 | PARALLEL |
| `Ls` | 列出目录内容 | PARALLEL |
| `Cron` | 管理当前 Agent 的定时任务 | SEQUENTIAL |
| `ListMateTools` | 实时列出 Agent 或 Skill 的 Mate 工具 | PARALLEL |
| `CallMateTool` | 按名称调用 Mate 工具 | SEQUENTIAL |
| `Agent` | 执行当前 Agent 直接绑定的 Child | SEQUENTIAL |

`Read`、`Find`、`Grep` 和 `Ls` 的工作区固定为当前 `agent/{agentId}`。`Bash`、`Edit`、
`Write`、`Loop`、`Glob`、`EditDiff`、`activate_skill`、`spawn_agent` 和 `invoke_agent` 不属于
产品工具契约，也不会进入模型工具列表。

三入口缺省配置位于
[`application.yml`](modules/coding-agent-cli/src/main/resources/application.yml)：

```yaml
campusclaw:
  tools:
    runtime: [Read, Find, Grep, Ls, Cron, ListMateTools, CallMateTool, Agent]
    cron: [Read, Find, Grep, Ls, ListMateTools, CallMateTool, Agent]
    child-agent: [Read, Find, Grep, Ls, ListMateTools, CallMateTool]
```

配置列表完全替换缺省值，显式空数组表示不装配工具；未知、重复、大小写错误或已禁用的名称
会导致启动失败。首版 Child 最大深度为 1，因此 Child profile 不公开 `Agent`。

## 受管 Agent 目录

每个 Agent 的工作区为 `agent/{agentId}`，受管元数据位于其 `.campusclaw` 子目录：

```text
agent/{agentId}/
└── .campusclaw/
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

`prepare(agentId)` 在完整缓存命中时不访问远端；目录缺失或不完整时先在同一 Agent 工作区内
构建 staging 目录，完整校验后发布。管理面 `refresh(agentId)` 总是重新拉取，失败时保留旧目录。
目录不生成或读取 `tools.json`；Mate 工具列表始终实时查询。

## HTTP Runtime

Runtime V1 的基础路径为 `/campusclaw-service/v1`，主要接口包括：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/agents/{agentId}/sessions` | 创建 Session |
| `GET` / `DELETE` | `/sessions/{sessionId}` | 查询或删除 Session |
| `POST` | `/sessions/{sessionId}/events` | 提交用户消息并返回本轮 SSE |
| `GET` | `/sessions/{sessionId}/events` | 分页查询当前分支 Entry |
| `GET` | `/sessions/{sessionId}/models` | 查询当前及可用模型 |
| `PUT` | `/sessions/{sessionId}/model` | 切换后续执行模型 |
| `PUT` | `/sessions/{sessionId}/thinking` | 切换 thinking |
| `POST` | `/sessions/{sessionId}/steers` | 提交高优先级控制消息 |
| `POST` | `/sessions/{sessionId}/follow-ups` | 提交 FIFO 后续消息 |
| `POST` | `/sessions/{sessionId}/abort` | 中止当前执行 |

字段级契约、SSE 生命周期和数据库说明见
[`campusclaw-http-v1-implementation.md`](docs/plans/campusclaw-http-v1-implementation.md)。

## 模型供应商

下表维护内置模型供应商及其部署凭据。供应商 ID 以代码中的 `Provider` 值为准。

<!-- supported-providers — SOURCE OF TRUTH: the Provider enum + ModelRegistry built-ins.
     Pinned by SupportedProvidersDocTest (modules/ai); when that test fails, update BOTH
     the canonical id list below AND the table that follows to match the code.
     BEGIN supported-providers
     anthropic, openai, mistral, azure-openai-responses, openai-codex, github-copilot,
     zai, kimi-coding, minimax, minimax-cn, xai, groq, cerebras, openrouter, huggingface
     END supported-providers -->

| 供应商 ID | 环境变量 |
|---|---|
| `anthropic` | `ANTHROPIC_API_KEY` / `ANTHROPIC_OAUTH_TOKEN` |
| `openai` | `OPENAI_API_KEY` |
| `mistral` | `MISTRAL_API_KEY` |
| `azure-openai-responses` | `AZURE_OPENAI_API_KEY` |
| `openai-codex` | `OPENAI_API_KEY` |
| `github-copilot` | `COPILOT_GITHUB_TOKEN` / `GH_TOKEN` / `GITHUB_TOKEN` |
| `zai` | `ZAI_API_KEY` |
| `kimi-coding` | `KIMI_API_KEY` |
| `minimax` | `MINIMAX_API_KEY` |
| `minimax-cn` | `MINIMAX_CN_API_KEY` |
| `xai` | `XAI_API_KEY` |
| `groq` | `GROQ_API_KEY` |
| `cerebras` | `CEREBRAS_API_KEY` |
| `openrouter` | `OPENROUTER_API_KEY` |
| `huggingface` | `HF_TOKEN` |

模型目录只暴露当前部署凭据可用、且在受管 Agent `bindingModels` 内的模型。凭据不写入
Agent 目录、Session 或工具缓存。

## 项目结构

```text
.
├── modules/
│   ├── ai/                  # LLM 抽象、供应商适配和模型注册
│   ├── agent-core/          # Agent 循环、Session、事件和工具 Pipeline
│   ├── cron/                # 定时任务模型、引擎、存储和 Agent 工具
│   └── coding-agent-cli/    # Spring Boot 服务、Runtime Host 和工具实现（历史目录名）
├── frontend/                # Vue 3 + TypeScript Runtime 调试客户端
├── mate-campusclaw/         # 从 modules/* 生成的公司集成镜像
├── agent/                   # 受管 Agent 示例和运行素材
├── docs/                    # 设计、部署和运行文档
├── scripts/                 # 镜像同步、Git hook 和辅助脚本
└── pom.xml                  # Maven 根工程，声明四个 Java 模块
```

模块依赖关系：

```text
ai -> agent-core -> cron -> coding-agent-cli
          \-----------------> coding-agent-cli
```

## 开发与验证

```bash
# 格式化并执行构建规则
./mvnw spotless:apply checkstyle:check

# 运行全部测试和完整验证
./mvnw test
./mvnw verify

# 同步并编译公司集成镜像
./scripts/sync-mate-campusclaw.sh

# 预览镜像差异
./scripts/sync-mate-campusclaw.sh --dry-run
```

`mate-campusclaw/` 是生成镜像，主源码只在 `modules/*` 修改。同步脚本从
`modules/{ai,agent-core,cron,coding-agent-cli}` 生成镜像并重写 Java 包名；不要双份手工维护。

## 相关文档

| 文档 | 用途 |
|---|---|
| [`campusmate-shared-config.md`](docs/designs/campusmate-shared-config.md) | CampusMate 单一服务地址、六 operation 目录和配置迁移 |
| [`tool-system-v2.md`](docs/designs/tool-system-v2.md) | 三入口、八工具、工作区和 Session 装配主设计 |
| [`coding-agent-cli.md`](docs/designs/coding-agent-cli.md) | Runtime HTTP 与公共 Session 设计 |
| [`mate-tool-client.md`](docs/designs/mate-tool-client.md) | Mate 实时发现、Session 缓存和名称调用 |
| [`cron.md`](docs/designs/cron.md) | Runtime-only Cron 与 Agent 隔离 |
| [`agent-delegation.md`](docs/designs/agent-delegation.md) | Child Execution 约束 |
| [`frontend/README.md`](frontend/README.md) | HTTP/SSE 调试客户端 |
