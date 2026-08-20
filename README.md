# CampusClaw

CampusClaw 是基于 Java 21 和 Spring Boot 3.4.1 构建的终端 AI 编程助手。它提供交互式 TUI、单次执行、RPC，以及面向前端和服务集成的 HTTP + SSE Runtime；同时支持多模型供应商、代码操作工具、会话持久化、Skill/Extension 扩展和定时任务。

> 本地启动与安装支持平台：macOS 与 Linux。Windows 启动和安装不在支持或维护范围内。

## 目录

- [功能概览](#功能概览)
- [快速开始](#快速开始)
- [运行模式](#运行模式)
- [CLI 用法](#cli-用法)
- [内置工具](#内置工具)
- [模型供应商](#模型供应商)
- [配置与数据目录](#配置与数据目录)
- [HTTP Runtime 与前端](#http-runtime-与前端)
- [项目结构](#项目结构)
- [开发与验证](#开发与验证)
- [mate-campusclaw 镜像同步](#mate-campusclaw-镜像同步)
- [部署与相关文档](#部署与相关文档)
- [故障排查](#故障排查)

## 功能概览

- **终端 Agent**：交互式 TUI、单次提问、非交互输出和 stdin/stdout JSONL RPC。
- **HTTP Runtime V1**：Spring MVC 提供 Session API，使用请求范围 SSE 返回执行事件。
- **多模型支持**：Anthropic、OpenAI、Mistral、ZAI、Kimi、MiniMax、OpenRouter 等供应商，以及自定义模型配置。
- **代码操作**：读取、写入、编辑、补丁、Shell、文件查找和内容搜索。
- **会话与扩展**：JSONL 会话持久化、Skill 包、Extension 注册点和子 Agent。
- **定时任务**：通过 `cron` 工具创建和管理持久化的后台 Agent 任务。
- **前端调试客户端**：`frontend/` 提供 Vue 3 + TypeScript + Vite 的 HTTP + SSE 调试页面。

## 快速开始

### 前置要求

- JDK 21
- 至少一个模型供应商的 API Key，或已经通过 `auth.json` 保存凭据
- 仅使用 CLI 时不需要 Node.js 和 Docker；运行 HTTP 前后端开发脚本时还需要 Node.js、npm 和 Docker

确认 Java 版本：

```bash
java -version
# 应输出 openjdk version "21.x.x"
```

JDK 21 安装示例：

| 操作系统 | 安装方式 |
|---|---|
| macOS | `brew install openjdk@21` |
| Ubuntu/Debian | `sudo apt install openjdk-21-jdk` |
| Fedora | `sudo dnf install java-21-openjdk-devel` |
| 通用 | [SDKMAN](https://sdkman.io/)：`sdk install java 21-tem` |

### 配置模型凭据

可以使用环境变量：

```bash
# macOS / Linux
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENAI_API_KEY="sk-..."
```

也可以在启动后使用 `/auth login <provider> <key>` 保存凭据，或参照 [`docs/settings.example.json`](docs/settings.example.json) 配置 `settings.json`。凭据解析优先级为：`auth.json`、`settings.json` 中的 `provider.<id>`、模型自身配置、环境变量。

### 启动 CLI

推荐使用启动脚本。脚本会自动寻找 JDK 21，首次启动或检测到源码变化时构建 CLI JAR，并自动向 JAR 注入 `cli` 子命令。

```bash
./campusclaw.sh -m glm-5
```

强制重新构建：

```bash
./campusclaw.sh --rebuild -m glm-5
```

如果自动检测不到 JDK 21，先设置 `JAVA_HOME`：

```bash
export JAVA_HOME=/path/to/jdk-21
```

### 启动 HTTP 前后端

本地开发可以使用 `start-dev.sh`。它会：

1. 创建或复用 openGauss Docker 容器；
2. 初始化 Runtime 所需的数据库和表结构；
3. 按后端源码变化构建 JAR；
4. 启动 Spring Boot 后端和 Vue/Vite 前端。

```bash
./start-dev.sh
```

默认端口为后端 `8080`、前端 `5173`、数据库映射端口 `35432`；端口被占用时脚本会选择下一个可用端口。数据库容器默认为 `campusclaw-opengauss-test`，按 `Ctrl-C` 停止前后端时容器会保留。

常用覆盖项：

| 环境变量 | 作用 |
|---|---|
| `CAMPUSCLAW_DB_PASSWORD` | 数据库密码 |
| `CAMPUSCLAW_DB_PORT` | 宿主机数据库端口 |
| `SERVER_PORT` | 后端端口 |
| `FRONTEND_PORT` | 前端端口 |
| `CAMPUSCLAW_AGENT_ROOT` | HTTP Runtime 使用的 Agent 根目录 |

## 运行模式

可执行 JAR 有两种入口：

| 命令 | 运行方式 | 适用场景 |
|---|---|---|
| `java -jar ...` | Spring Boot MVC HTTP 服务 | HTTP Runtime、前端和服务部署 |
| `java -jar ... cli ...` | 非 Web CLI 上下文 | 交互式 TUI、单次执行、RPC |
| `./campusclaw.sh ...` | `cli` 的 macOS/Linux 包装 | 日常终端使用，自动构建 |

CLI 的 `--mode` 支持以下值：

| 模式 | 说明 |
|---|---|
| `interactive` | 交互式 TUI，默认模式 |
| `one-shot` | 执行一次请求后退出 |
| `print` | 非交互输出模式；也可以使用 `-p/--print` |
| `rpc` | 通过 stdin/stdout 交换 JSONL 消息 |

## CLI 用法

```text
campusclaw [OPTIONS] [PROMPT...]
```

常用选项：

| 选项 | 说明 | 示例 |
|---|---|---|
| `-m, --model` | 指定模型 | `-m claude-sonnet-4` |
| `--provider` | 指定供应商 | `--provider openai` |
| `--api-key` | 覆盖已解析的 API Key | `--api-key sk-...` |
| `--thinking` | 思考级别：`off/minimal/low/medium/high/xhigh` | `--thinking high` |
| `--mode` | 选择执行模式 | `--mode rpc` |
| `--proxy` | 指定 HTTP/SOCKS5 代理 | `--proxy http://127.0.0.1:7890` |
| `--cwd` | 设置工作目录 | `--cwd /path/to/project` |
| `--agent-id` | 加载受管控 Agent | `--agent-id my-agent` |
| `--tools` | 以逗号分隔指定工具 | `--tools read,bash,edit` |
| `--no-tools` | 禁用内置工具 | `--no-tools` |
| `-p, --print` | 非交互处理 Prompt 后退出 | `-p "解释这段代码"` |
| `--system-prompt` | 替换默认系统提示词 | `--system-prompt "..."` |
| `--append-system-prompt` | 追加系统提示词 | `--append-system-prompt "..."` |
| `--models` | 供 Ctrl+P 切换的模型过滤条件 | `--models "claude*,gpt*"` |
| `--list-models [pattern]` | 列出可用模型，可选过滤条件 | `--list-models claude` |
| `-c, --continue` | 继续上一次会话 | `-c` |
| `-r, --resume` | 选择历史会话恢复 | `-r` |
| `--session <path>` | 使用指定会话文件 | `--session /tmp/session.jsonl` |
| `--fork <path>` | 从已有会话创建分支 | `--fork /tmp/session.jsonl` |
| `--no-session` | 不保存会话 | `--no-session` |
| `--export <in> [out]` | 将会话导出为 HTML | `--export session.jsonl out.html` |
| `--offline` | 禁止启动阶段的网络操作 | `--offline` |
| `--verbose` | 输出详细启动信息 | `--verbose` |

示例：

```bash
# 交互式 TUI
./campusclaw.sh -m claude-sonnet-4

# 单次提问
./campusclaw.sh -m glm-5 -p "这个项目的架构是什么？"

# 使用 @ 前缀把文件内容加入 Prompt
./campusclaw.sh -m glm-5 "请审查这个文件 @src/main/java/App.java"

# 列出模型
./campusclaw.sh --list-models

# RPC：stdin/stdout JSONL
./campusclaw.sh --mode rpc -m glm-5
```

直接运行 JAR 时要显式写出 `cli`，例如：

```bash
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar cli -m glm-5
```

## 内置工具

CLI 默认提供以下 8 个本地代码操作工具：

| 工具 | 功能 |
|---|---|
| `read` | 读取文件内容，支持行范围和图片 |
| `write` | 创建或覆盖文件 |
| `edit` | 对文件执行精确文本替换 |
| `editdiff` | 使用 unified diff 修改文件 |
| `bash` | 在工作目录执行 Bash 命令 |
| `glob` | 按 glob 模式查找文件 |
| `grep` | 按正则搜索文件内容 |
| `ls` | 列出目录内容 |

此外，运行时还可以根据配置加载：

- `cron`：管理持久化定时任务；
- MateService 工具：通过 `ListMateTool`、`CallMateTool` 和 `MateToolClient` 访问受管控工具；
- Skill 工具、Extension 工具和子 Agent 工具。

## 模型供应商

下表维护有内置模型的供应商及其环境变量。供应商 ID 以代码中的 `Provider` 值为准。

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

也可以通过 `provider` 配置自定义 `apiKey`、`baseURL` 和请求头，或通过 `customModels` 注册 OpenAI 兼容模型。完整示例见 [`docs/settings.example.json`](docs/settings.example.json)。

## 配置与数据目录

CampusClaw 区分用户级配置和项目级配置：

| 内容 | 路径 |
|---|---|
| 用户级 Agent 根目录 | `~/.campusclaw/agent/` |
| 用户级设置 | `~/.campusclaw/agent/settings.json` |
| 用户级凭据 | `~/.campusclaw/agent/auth.json` |
| 用户级快捷键 | `~/.campusclaw/agent/keybindings.json` |
| 用户级会话 | `~/.campusclaw/agent/sessions/` |
| 用户级 Skill | `~/.campusclaw/agent/skills/` |
| 用户级 Prompt | `~/.campusclaw/agent/prompts/` |
| 项目级设置 | `<工作目录>/.campusclaw/settings.json` |
| 项目级 Skill | `<工作目录>/.campusclaw/skills/` |

常用设置项：

| 设置项 | 说明 |
|---|---|
| `model` / `defaultModel` | 默认模型；`model` 优先 |
| `defaultProvider` | 默认供应商 |
| `defaultThinkingLevel` | 默认思考级别 |
| `enabledModels` | 启用的模型列表 |
| `customModels` | 自定义模型定义 |
| `provider` | 供应商级 API Key、Base URL 和请求头 |
| `packages` / `extensions` | 已安装的 Skill 包和 Extension |
| `sessionDir` | 会话目录配置 |

启动时会自动创建用户级目录和默认 `AGENTS.md`。项目上下文还可以通过工作目录中的 `AGENTS.md` 提供。

## HTTP Runtime 与前端

### HTTP 服务

不带 `cli` 子命令时，JAR 启动 Spring MVC HTTP 服务，默认监听 `0.0.0.0:8080`。Runtime V1 的基础路径为 `/campusclaw-service/v1`，使用 openGauss/PostgreSQL 兼容数据库持久化 Session 和 Entry。

主要接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/agents/{agent_id}/sessions` | 创建 Session |
| `GET` / `DELETE` | `/sessions/{session_id}` | 查询或删除 Session |
| `POST` | `/sessions/{session_id}/events` | 提交 `user.message`，返回本次请求的 SSE |
| `GET` | `/sessions/{session_id}/events` | 查询当前分支历史 Entry |
| `GET` / `PUT` | `/sessions/{session_id}/model` | 查询或切换模型 |
| `PUT` | `/sessions/{session_id}/thinking` | 切换深度思考 |
| `POST` | `/sessions/{session_id}/steers` | 发送高优先级控制消息 |
| `POST` | `/sessions/{session_id}/follow-ups` | 发送 FIFO 后续消息 |
| `POST` | `/sessions/{session_id}/abort` | 中止当前执行 |

SSE 是单次请求范围的响应流，收到 `stream.end` 或 `stream.error` 后结束；客户端断开连接不等于中止服务端执行。完整接口语义、鉴权、数据库结构和验证证据见 [`docs/plans/campusclaw-http-v1-implementation.md`](docs/plans/campusclaw-http-v1-implementation.md)。

直接启动 HTTP 服务时，请按部署环境设置 `GAUSSDB_URL`、`GAUSSDB_USER`、`GAUSSDB_PASSWORD`、`GAUSSDB_SCHEMA` 和 `GAUSSDB_SSL_MODE` 等配置。日常本地开发优先使用上面的 `start-dev.sh`。

### Vue 前端

`frontend/` 是独立的 Vue 3 + TypeScript + Vite 调试客户端：

```bash
cd frontend
npm ci
npm run dev
```

默认访问 `http://localhost:5173`，开发服务器会把 `/campusclaw-service` 代理到 `http://localhost:8080`。质量命令：

```bash
npm run typecheck
npm run build
npm audit --audit-level=high
```

## 项目结构

```text
.
├── modules/
│   ├── ai/                  # LLM 抽象、多供应商适配、流式消息和模型注册
│   ├── tui/                 # JLine/Lanterna 终端 UI 组件
│   ├── agent-core/          # Agent 循环、状态、事件和工具执行管线
│   ├── cron/                # 定时任务模型、引擎、存储和 Agent 工具
│   ├── coding-agent-cli/    # Spring Boot 应用、CLI、TUI、HTTP Runtime 和工具实现
│   └── k8s/                 # Kubernetes 部署清单，不参与 Maven 构建
├── frontend/                # Vue 3 + TypeScript + Vite HTTP/SSE 调试客户端
├── mate-campusclaw/         # 面向 mate 父项目的单模块包名重写镜像
├── agent/                   # 仓库内受管控 Agent 示例和运行素材
├── docs/                    # 架构、设计、部署、运行和故障排查文档
├── k8s/                     # MateService 集成部署示例
├── scripts/                 # 镜像同步、Git hook 和开发辅助脚本
├── pom.xml                  # Maven 根工程，声明 5 个 Java 模块
├── campusclaw.sh            # macOS/Linux CLI 启动脚本
├── start-dev.sh             # 本地 HTTP 前后端开发脚本
└── README.md
```

Maven 模块依赖关系：

```text
ai ─────────────┐
                ├──→ agent-core ──┬──→ cron ─────┐
tui ────────────┤                 │              │
                └─────────────────┴──────────────┴──→ coding-agent-cli
```

| 模块 | Artifact | 职责 |
|---|---|---|
| `modules/ai` | `campusclaw-ai` | 统一 LLM 类型、Provider、模型注册和 Reactor 流式调用 |
| `modules/tui` | `campusclaw-tui` | 终端、ANSI 和可复用 TUI 组件 |
| `modules/agent-core` | `campusclaw-agent-core` | Agent 门面、循环、状态、事件和工具执行 |
| `modules/cron` | `campusclaw-cron` | 定时任务调度、运行记录和 Agent 集成 |
| `modules/coding-agent-cli` | `campusclaw-coding-agent` | 最终 Spring Boot 应用、CLI、HTTP Runtime、工具和会话管理 |

## 开发与验证

项目使用 Maven Wrapper，要求 JDK 21：

```bash
# 编译
./mvnw compile

# 运行所有测试
./mvnw test

# 完整验证
./mvnw verify

# 构建 coding-agent-cli fat JAR
./mvnw package -pl modules/coding-agent-cli -am -DskipTests

# 运行单个测试类或方法
./mvnw -pl modules/agent-core test -Dtest=AgentLoopTest
./mvnw -pl modules/agent-core test -Dtest=AgentLoopTest#name

# 格式化 Java 源码
./mvnw spotless:apply
```

> 如果默认 JDK 不是 21，需在命令前设置 `JAVA_HOME`。

提交前建议至少执行 `./mvnw verify`、`./mvnw spotless:check` 和 `git diff --check`。

`mvnw` 保留 Maven Wrapper 上游的 Cygwin/MinGW 兼容逻辑，允许用户在 Windows Git Bash 中自行尝试构建；该路径不属于项目支持或持续验证范围。

技术栈：Java 21、Spring Boot 3.4.1、Project Reactor、Spring MVC、Spring WebClient、Picocli 4.7.6、JLine 3.26.2、Lanterna 3.1.2 和 Maven。

## mate-campusclaw 镜像同步

`mate-campusclaw/` 是对接公司 `mate` 父项目的单模块镜像。同步脚本从 `modules/{ai,tui,agent-core,cron,coding-agent-cli}` 生成临时构建树，把包名从 `com.campusclaw` 重写为 `com.huawei.hicampus.mate.matecampusclaw`，再应用到镜像并执行编译验证。

```bash
# 同步并编译验证
./scripts/sync-mate-campusclaw.sh

# 预览同步差异，不写入镜像
./scripts/sync-mate-campusclaw.sh --dry-run

# 跳过镜像编译验证
./scripts/sync-mate-campusclaw.sh --no-verify

# 只生成 build/mate-campusclaw，不修改镜像
./scripts/sync-mate-campusclaw.sh --no-apply
```

`mate-campusclaw/` 下没有对应 `modules/*` 来源的新文件，需要登记到 `scripts/sync-mate-exclude.txt`，否则后续 `rsync --delete` 可能删除。新 clone 后可启用 pre-push 校验：

```bash
git config core.hooksPath scripts/git-hooks
```

## 部署与相关文档

| 文档 | 用途 |
|---|---|
| [`docs/module-architecture.md`](docs/module-architecture.md) | 模块职责、包结构和依赖关系 |
| [`docs/plans/campusclaw-http-v1-implementation.md`](docs/plans/campusclaw-http-v1-implementation.md) | HTTP Runtime V1 接口、SSE、持久化和验证证据 |
| [`frontend/README.md`](frontend/README.md) | 前端工作流和 SSE 语义 |
| [`modules/k8s/README.md`](modules/k8s/README.md) | Kubernetes 单容器部署示例 |
| [`docs/settings.example.json`](docs/settings.example.json) | 配置文件示例 |
| [`docs/designs/`](docs/designs/) | 各模块和运行时设计文档 |

Kubernetes 单容器示例位于 `modules/k8s/`；根目录 `k8s/` 主要保存 MateService 集成部署清单。构建镜像：

```bash
docker build -t campusclaw:latest .
```

## 故障排查

### JDK 版本不兼容

如果出现 `Unsupported class file major version` 或编译失败，确认 `java -version` 为 21，并设置正确的 `JAVA_HOME`。启动脚本会自动寻找 JDK 21，Maven 命令则使用当前 shell 的 Java。

### API 返回 401 / Unauthorized

确认供应商 ID 与环境变量匹配，或使用 `--api-key` 临时覆盖。也可以执行 `--list-models` 检查模型是否已注册。

### 需要代理或遇到连接超时

通过 `--proxy` 显式指定代理，例如：

```bash
./campusclaw.sh --proxy http://127.0.0.1:7890 -m glm-5
```

### TUI 乱码或无颜色

- macOS/Linux 确认 `TERM` 不为 `dumb`；
- 如果是前端页面问题，确认后端端口和 `VITE_BACKEND_URL`/Vite proxy 配置一致。

### 修改源码后行为没有变化

`campusclaw.sh` 和 `start-dev.sh` 会检测源码变化并按需重建。仍未更新时可以强制构建：

```bash
./campusclaw.sh --rebuild -m glm-5
./mvnw package -pl modules/coding-agent-cli -am -DskipTests
```
