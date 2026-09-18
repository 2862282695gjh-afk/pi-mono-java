# CampusClaw

CampusClaw 是基于 Java 21 和 Spring Boot 3.4.5 构建的 ToB Agent Runtime 服务。产品通过
HTTP + SSE 提供受管 Agent 会话，Runtime、Cron 和 Child Agent 共用同一套 Session 装配、
工具执行、错误和取消语义。

CampusClaw 不提供 CLI、TUI、RPC 或本地安装入口。`modules/coding-agent-cli` 是沿用的历史
模块目录名，当前产物仅作为 Spring Boot 服务启动。

## 功能概览

- **HTTP Runtime V1**：创建和管理 Agent Session，以请求范围 SSE 投影执行事件；
- **受管 Agent 目录**：从 CampusMate 准备 Agent、Skill 和直接绑定 Child 的运行目录；
- **固定工具集合**：按 Runtime、Cron、Child profile 装配 PascalCase 内置工具；
- **计划模式**：先制作完整方案，审核批准后继续实施，并通过显式任务事件展示进度；
- **工作区隔离**：本地文件工具只能读取当前 `agent/{agentId}`，拒绝符号链接和 realpath 越界；
- **Mate 工具**：实时发现当前 Agent/Skill 的工具，按名称解析并执行；
- **Cron 与 Child**：Cron Job 自动绑定当前 Agent，Child 只允许调用直接绑定身份；
- **CampusMate 模型调用**：受管 Agent 快照提供模型 ID，统一通过 `campusmate` Provider 调用。

## 快速开始

### 前置要求

- JDK 21；
- openGauss 或 PostgreSQL 兼容数据库；
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
| `CAMPUSMATE_BASE_URL` | Model、受管 Runtime 与 Tool 共享的 CampusMate 服务地址；主模块必填，公司镜像缺省为 `https://localhost:8591` |
| `CAMPUSMATE_MODEL_*` | CampusMate Model Provider 的 API、超时和 token 参数 |
| `CAMPUSMATE_RUNTIME_*` | CampusMate Runtime 客户端的超时参数 |
| `CAMPUSMATE_*_PATH*` | 六个 CampusMate HTTP operation path 或 path template |
| `CAMPUSCLAW_EVENT_CURSOR_SECRET` | Runtime 事件游标签名密钥 |

生产环境应显式配置数据库密码、稳定高熵的事件游标密钥、服务地址和 TLS 参数。

## 内置工具

公共 `BuiltInToolName` 包含以下名称；各入口按 profile 选择：

| 工具 | 作用 | 执行模式 |
|---|---|---|
| `Read` | 分块读取 UTF-8 文本文件 | PARALLEL |
| `Find` | 按 glob 查找文件 | PARALLEL |
| `Grep` | 按正则或字面量搜索文件内容 | PARALLEL |
| `Ls` | 列出目录内容 | PARALLEL |
| `Cron` | 管理当前 Agent 的定时任务 | SEQUENTIAL |
| `ListMateTools` | 实时列出 Agent 或 Skill 的 Mate 工具 | PARALLEL |
| `CallMateTool` | 按名称调用 Mate 工具 | SEQUENTIAL |
| `Agent` | 执行当前 Agent 直接绑定的 Child | SEQUENTIAL |
| `ExitPlanMode` | Runtime 根执行提交完整计划并等待审核 | SEQUENTIAL |
| `TodoUpdate` | 返回实施任务的完整进度快照 | SEQUENTIAL |
| `AskUserQuestion` | Runtime 根执行提交结构化问题并等待整批回答 | SEQUENTIAL |

`Read`、`Find`、`Grep` 和 `Ls` 的工作区固定为当前 `agent/{agentId}`。`Bash`、`Edit`、
`Write`、`Loop`、`Glob`、`EditDiff`、`activate_skill`、`spawn_agent` 和 `invoke_agent` 不属于
产品工具契约，也不会进入模型工具列表。仓库不保留 `Bash`、`Edit`、`Write` 的工具本体、
专用执行器、文件操作抽象、配置或兼容入口。

三入口缺省配置位于
[`application.yml`](modules/coding-agent-cli/src/main/resources/application.yml)：

```yaml
campusclaw:
  tools:
    runtime: [Read, Find, Grep, Ls, Cron, ListMateTools, CallMateTool, Agent, ExitPlanMode, TodoUpdate, AskUserQuestion]
    cron: [Read, Find, Grep, Ls, ListMateTools, CallMateTool, Agent]
    child-agent: [Read, Find, Grep, Ls, ListMateTools, CallMateTool]
```

配置列表完全替换缺省值，显式空数组表示不装配工具；未知、重复、大小写错误或已禁用的名称
会导致启动失败。首版 Child 最大深度为 1，因此 Child profile 不公开 `Agent`。
Runtime 的 plan/build 使用相同工具定义；进入 plan 需要实际装配 `ExitPlanMode`。
计划模式通过 `PLAN.md`、`BUILD.md` 约束调研与实施阶段，具体业务权限仍由工具权限规则决定。

## 受管 Agent 目录

每个 Agent 的工作区为 `agent/{agentId}`，受管元数据位于其 `.campusclaw` 子目录：

```text
agent/{agentId}/
└── .campusclaw/
    ├── agent.json
    ├── settings.json
    ├── SYSTEM.md
    ├── PLAN.md（可选）
    ├── BUILD.md（可选）
    ├── agents/{agentName}.json
    └── skills/{skillName}/
        ├── skill.json
        ├── SKILL.md
        ├── references/
        └── templates/
```

`prepare(agentId)` 在完整缓存命中时不访问远端；目录缺失或不完整时先在同一 Agent 工作区内
构建 staging 目录，完整校验后发布。管理面 `refresh(agentId)` 总是重新拉取，失败时保留旧目录。
`PLAN.md` 和 `BUILD.md` 分别完整替换内置同名模式正文；单个文件不存在时只回退该模式的内置正文。
每次执行准备时固定基础 SYSTEM、Skill 摘要和两种模式正文，批准续跑、修订与压缩都复用该快照。
两个覆盖文件会随目录刷新保留；空白、目录、链接、非法 UTF-8、超限或不可读文件会使准备失败。
目录不生成或读取 `tools.json`；Mate 工具列表始终实时查询。
Agent 元数据的 `mode` 为 `build|plan` 字符串，缺省为 `build`；只用于初始化新 Session。
已有 Session 独立维护模式，刷新 Agent 元数据不会覆盖会话模式。

## HTTP Runtime

Runtime V1 的基础路径为 `/campusclaw-service/v1`，主要接口包括：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/agents/{agentId}/sessions` | 创建 Session，继承 Agent 默认模式 |
| `GET` / `DELETE` | `/sessions/{sessionId}` | 查询或删除 Session |
| `POST` | `/sessions/{sessionId}` | 按提交字段更新 displayName、modelId、thinking、mode |
| `POST` | `/sessions/{sessionId}/events` | 提交消息、停止、工具确认或计划确认，返回请求范围 SSE |
| `GET` | `/sessions/{sessionId}/events` | 分页读取当前分支权威公共事件 |
| `GET` | `/sessions/{sessionId}/models` | 查询当前及可用模型 |
| `GET` | `/sessions/{sessionId}/commands` | 获取可用命令清单 |
| `POST` | `/sessions/{sessionId}/command` | 执行 Builtin 或 Skill 命令 |

Session 更新要求强 `If-Match`，允许一次提交一个或多个字段；响应和 ETag 来自同一更新快照。
仅修改名称允许在 running 状态执行，提交 modelId、thinking 或 mode 时要求 idle。
计划审核通过 Events 接口提交；批准事务会在原执行中把模式改为 build。

详见[HTTP 实施记录](docs/plans/campusclaw-http-v1-implementation.md)和
[计划模式实施说明](docs/plans/campusclaw-plan-mode-implementation.md)。

<a id="模型供应商"></a>

## 模型与 Provider

产品默认只装配 `CampusMateProvider`，Provider ID 为 `campusmate`。Runtime 使用同一受管 Agent
快照中的 `defaultModel` 与 `bindingModels` 构造模型；CampusClaw 不保存第三方模型端点或凭据。

`ModelRegistry` 不再提供内置模型。它保留运行时注册、查询、成本计算以及 classpath
`/campusclaw-models.json`、用户 `~/.campusclaw/agent/models.json` 两级可选加载；没有外部 JSON
时目录为空。`ApiProviderRegistry` 默认也可以为空，嵌入方仍可显式注册和注销自定义 API
适配器。未知适配器会明确失败，不外联，也不自动转到 CampusMate。

## 项目结构

```text
.
├── modules/
│   ├── common/              # ClawConstants 共享业务常量，文件内按领域分组
│   ├── ai/                  # LLM 抽象、供应商适配和模型注册
│   ├── agent-core/          # Agent 循环、Session、事件和工具 Pipeline
│   ├── cron/                # 定时任务模型、引擎、存储和 Agent 工具
│   └── coding-agent-cli/    # Spring Boot 服务、Runtime Host 和工具实现（历史目录名）
├── frontend/                # Vue 3 + TypeScript Runtime 调试客户端
├── campusclaw/              # 从 modules/* 生成的公司集成镜像（不加入根 Reactor）
├── agent/                   # 受管 Agent 示例和运行素材
├── docs/                    # 设计、部署和运行文档
├── scripts/                 # 镜像同步、Git hook 和辅助脚本
└── pom.xml                  # Maven 根工程，声明五个 Java 模块
```

模块依赖关系：

直接依赖：ai → common；agent-core → ai；cron → agent-core；
coding-agent-cli → common、ai、agent-core、cron。common 不依赖业务模块。

常量组织方式见[共享常量设计](docs/designs/shared-constants.md)。

## 开发与验证

开发流程、编码规范和验证要求统一维护在 [AGENTS.md](AGENTS.md)。修改前先阅读该手册及相关源码与设计文档。

```bash
# 格式化并执行构建规则
./mvnw spotless:apply checkstyle:check

# 运行全部测试和完整验证
./mvnw test
./mvnw verify

# 在可解析 NativeParent 的公司 Maven 环境同步并编译镜像
./scripts/sync-campusclaw.sh

# 普通本地环境显式跳过公司构建，仅同步或预览差异
./scripts/sync-campusclaw.sh --no-verify
./scripts/sync-campusclaw.sh --dry-run --no-verify
```

`campusclaw/` 是生成镜像，主源码只在 `modules/*` 修改。同步脚本从
`modules/{common,ai,agent-core,cron,coding-agent-cli}` 生成镜像并把 Java 根包重写为
`com.huawei.hicampus.claw`；不要双份手工维护。镜像使用公司父 POM
`com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT`，项目坐标为
`com.huawei.campus:claw:1.0-SNAPSHOT`，默认产物为
`campusclaw/target/claw-1.0-SNAPSHOT.jar`。父 POM 不可解析时同步脚本会失败，不会静默跳过构建。

## 相关文档

| 文档 | 用途 |
|---|---|
| [`campusclaw-corporate-module.md`](docs/designs/campusclaw-corporate-module.md) | 公司镜像生成、包重写、父 POM 与构建边界 |
| [`campusmate-shared-config.md`](docs/designs/campusmate-shared-config.md) | CampusMate 单一服务地址、六 operation 目录和配置迁移 |
| [`tool-system-v2.md`](docs/designs/tool-system-v2.md) | 三入口、工作区和 Session 装配；计划工具增量见下项 |
| [计划模式实施说明](docs/plans/campusclaw-plan-mode-implementation.md) | 模式、审核、实施进度和外部 Mate 接入边界 |
| [`coding-agent-cli.md`](docs/designs/coding-agent-cli.md) | Runtime HTTP 与公共 Session 设计 |
| [`mate-tool-client.md`](docs/designs/mate-tool-client.md) | Mate 实时发现、Session 缓存和名称调用 |
| [`cron.md`](docs/designs/cron.md) | Runtime-only Cron 与 Agent 隔离 |
| [`agent-delegation.md`](docs/designs/agent-delegation.md) | Child Execution 约束 |
| [`frontend/README.md`](frontend/README.md) | HTTP/SSE 调试客户端 |
