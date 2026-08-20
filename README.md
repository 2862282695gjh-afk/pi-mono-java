# XXXClaw

基于 Java 21 + Spring Boot 3.4.1 实现的终端 AI 编程助手，支持多模型供应商，提供交互式 TUI 界面和丰富的代码操作工具。

> 支持平台：macOS 与 Linux。Windows 不在支持或维护范围内。

## 前置要求

- **JDK 21**
- 至少一个 AI 供应商的 API Key（见「支持的供应商」章节）

### 安装 JDK 21

| 操作系统 | 安装方式 |
|----------|----------|
| macOS | `brew install openjdk@21` |
| Linux (Ubuntu/Debian) | `sudo apt install openjdk-21-jdk` |
| Linux (Fedora) | `sudo dnf install java-21-openjdk-devel` |
| 通用 | [SDKMAN](https://sdkman.io/)：`sdk install java 21-tem` |

安装后确认版本：

```bash
java -version
# 应输出 openjdk version "21.x.x"
```

## 快速开始

### 1. 设置 API Key 环境变量

根据你使用的模型供应商，配置对应的环境变量：

添加到 `~/.zshrc` 或 `~/.bashrc`：

```bash
export ANTHROPIC_API_KEY="sk-ant-..."   # Anthropic (Claude)
export OPENAI_API_KEY="sk-..."          # OpenAI
# 其他供应商见「支持的供应商」章节
```

### 2. 启动

#### 一键启动（推荐）

脚本自动检测 JDK 21、首次自动构建、源码变更后自动重建。

```bash
./campusclaw.sh -m glm-5
```

传入 `--rebuild` 可强制重新构建：

```bash
./campusclaw.sh --rebuild -m glm-5
```
> 如果系统安装了多个 JDK 版本且脚本无法自动找到 JDK 21，请手动设置 `JAVA_HOME`：
>
> ```bash
> export JAVA_HOME=/path/to/jdk-21
> ```

#### Maven 开发模式

```bash
# 默认启动 Spring MVC HTTP 服务
./mvnw -pl modules/coding-agent-cli spring-boot:run

# 显式进入 CLI
./mvnw -pl modules/coding-agent-cli spring-boot:run -Dspring-boot.run.arguments='cli -m glm-5'
```

#### 手动构建后运行

```bash
# 构建 fat JAR
./mvnw package -pl modules/coding-agent-cli -am -DskipTests

# 默认启动 Spring MVC HTTP 服务（0.0.0.0:8080）
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar

# 以 CLI 运行
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar cli -m glm-5
```

#### 常用 Maven 命令

| 命令 | 说明 |
|------|------|
| `./mvnw compile` | 编译所有模块 |
| `./mvnw test` | 运行测试 |
| `./mvnw verify` | 完整构建（含测试） |
| `./mvnw package -DskipTests` | 构建全部 JAR（跳过测试） |
| `./mvnw clean` | 清理 target/ |
| `./mvnw spotless:apply` | 格式化代码 |

> 如果 Maven 报 JDK 版本不兼容，请在命令前设置 `JAVA_HOME=/path/to/jdk-21`。

## 用法

```
campusclaw [OPTIONS] [PROMPT...]
```

### 核心选项

| 选项 | 说明 | 示例 |
|------|------|------|
| `-m, --model` | 指定模型 | `-m claude-sonnet-4` |
| `--provider` | 指定供应商 | `--provider openai` |
| `--api-key` | 覆盖 API Key | `--api-key sk-...` |
| `--thinking` | 思考级别：off/minimal/low/medium/high/xhigh | `--thinking high` |
| `-p, --print` | 非交互模式，输出后退出 | `-p "解释这段代码"` |
| `--mode` | CLI 执行模式：interactive/one-shot/rpc/print | `--mode rpc` |
| `--tools` | 指定启用的工具（逗号分隔） | `--tools read,bash,edit` |
| `--no-tools` | 禁用所有内置工具 | |

### 会话管理

| 选项 | 说明 |
|------|------|
| `-c, --continue` | 继续上一次会话 |
| `-r, --resume` | 交互式选择历史会话 |
| `--session <path>` | 使用指定会话文件 |
| `--fork <path>` | 基于已有会话创建分支 |
| `--no-session` | 临时会话（不保存） |
| `--export <in> [out]` | 导出会话为 HTML |

### 其他选项

| 选项 | 说明 |
|------|------|
| `--system-prompt` | 替换默认系统提示词 |
| `--append-system-prompt` | 追加内容到系统提示词 |
| `--models` | 逗号分隔的模型列表，用于 Ctrl+P 切换 |
| `--list-models [pattern]` | 列出可用模型并退出 |
| `--cwd <path>` | 设置工作目录 |
| `--verbose` | 显示详细启动信息 |

### 使用示例

```bash
# 交互模式（默认）
./campusclaw.sh -m claude-sonnet-4

# 单次提问
./campusclaw.sh -m glm-5 -p "这个项目的架构是什么？"

# 高级思考模式
./campusclaw.sh -m claude-sonnet-4 --thinking high

# 继续上次会话
./campusclaw.sh -m glm-5 -c

# 使用文件内容作为输入（@ 前缀）
./campusclaw.sh -m glm-5 "请审查这个文件 @src/main/java/App.java"

# 列出所有可用模型
./campusclaw.sh --list-models

# HTTP 服务（标准 Spring Boot 配置；此命令不走 CLI 包装脚本）
SERVER_PORT=3000 java -jar modules/coding-agent-cli/target/campusclaw-agent.jar

# RPC 模式（stdin/stdout JSONL，供进程间通信）
./campusclaw.sh --mode rpc -m glm-5
```

> 不带 `cli` 子命令时，JAR 作为标准 Spring Boot MVC 服务启动；`campusclaw.sh` 是 CLI 包装脚本，会自动补充 `cli`。Runtime V1 使用 HTTP + 请求范围 SSE，实施边界和验证证据见 [CampusClaw HTTP V1 实施记录](docs/plans/campusclaw-http-v1-implementation.md)。本仓不再维护 OpenAPI 副本。

## 内置工具

Agent 内置 8 个代码操作工具：

| 工具 | 功能 |
|------|------|
| `read` | 读取文件内容，支持行范围和图片 |
| `write` | 写入文件 |
| `edit` | 按行编辑文件 |
| `editdiff` | 应用 unified diff 补丁 |
| `bash` | 执行 shell 命令 |
| `glob` | 按模式搜索文件 |
| `grep` | 按正则搜索文件内容 |
| `ls` | 列出目录内容 |

## 支持的供应商

<!-- supported-providers — SOURCE OF TRUTH: the Provider enum + ModelRegistry built-ins.
     Pinned by SupportedProvidersDocTest (modules/ai); when that test fails, update BOTH
     the canonical id list below AND the table that follows to match the code.
     BEGIN supported-providers
     anthropic, openai, mistral, azure-openai-responses, openai-codex, github-copilot,
     zai, kimi-coding, minimax, minimax-cn, xai, groq, cerebras, openrouter, huggingface
     END supported-providers -->

| 供应商 | 环境变量 |
|--------|----------|
| Anthropic (Claude) | `ANTHROPIC_API_KEY` |
| OpenAI | `OPENAI_API_KEY` |
| Azure OpenAI | `AZURE_OPENAI_API_KEY` |
| Mistral | `MISTRAL_API_KEY` |
| ZAI | `ZAI_API_KEY` |
| Kimi | `KIMI_API_KEY` |
| MiniMax | `MINIMAX_API_KEY` |
| xAI (Grok) | `XAI_API_KEY` |
| Groq | `GROQ_API_KEY` |
| Cerebras | `CEREBRAS_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` |
| HuggingFace | `HF_TOKEN` |
| GitHub Copilot | `COPILOT_GITHUB_TOKEN` / `GH_TOKEN` |

## 配置文件

用户配置路径：`~/.campusclaw/settings.json`。

可设置项：

- `defaultModel` — 默认模型
- `defaultThinkingLevel` — 默认思考级别
- `enabledModels` — 启用的模型列表
- `customModels` — 自定义模型（自定义 baseUrl、apiKey 等）
- `packages` — 已安装的扩展包

## 项目结构

```
campusclaw/
├── modules/
│   ├── ai/                  # campusclaw-ai — 统一 LLM 调用层，多供应商适配
│   ├── agent-core/          # campusclaw-agent-core — Agent 循环、工具执行管线
│   ├── coding-agent-cli/    # campusclaw-coding-agent — CLI 入口 + TUI 界面
│   └── tui/                 # campusclaw-tui — 终端 UI 组件（JLine + Lanterna）
├── pom.xml                  # 根构建配置（Maven）
├── campusclaw.sh            # 启动脚本（macOS / Linux）
└── README.md
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Java 21（Records, Sealed Interfaces, Pattern Matching） |
| 框架 | Spring Boot 3.4.1 |
| 异步 | Project Reactor (Mono/Flux) |
| HTTP 服务端 | Spring MVC + Java 21 虚拟线程 + SSE |
| 模型 HTTP 客户端 | Spring WebClient + Reactor 流式处理 |
| CLI | Picocli 4.7.6 |
| 终端 | JLine 3.26.2 + Lanterna 3.1.2 |
| 构建 | Maven 3.9.11 |

## 开发

```bash
# 构建所有模块
./mvnw verify

# 运行测试
./mvnw test

# 仅构建 JAR
./mvnw package -pl modules/coding-agent-cli -am -DskipTests
```

> 如果默认 JDK 不是 21，需在命令前设置 `JAVA_HOME`。

### 同步 modules/* 到 mate-campusclaw

`mate-campusclaw/` 是为对接公司 `mate` 父项目而维护的单模块镜像，包名重写为 `com.huawei.hicampus.mate.matecampusclaw`。日常在 `modules/*` 里开发，通过下面的脚本把变更同步进 `mate-campusclaw/`：

```bash
./scripts/sync-mate-campusclaw.sh             # 同步 + 编译验证
./scripts/sync-mate-campusclaw.sh --dry-run   # 预览改动，不写盘
./scripts/sync-mate-campusclaw.sh --no-verify # 跳过 mvn compile
./scripts/sync-mate-campusclaw.sh --no-apply  # 仅生成 build/，不动 mate-campusclaw/
```

脚本三阶段：

1. **Stage** — 把 `modules/{ai,tui,agent-core,assistant,cron,coding-agent-cli}` 复制到 `build/mate-campusclaw/`，并把 `com.campusclaw` 替换成 `com.huawei.hicampus.mate.matecampusclaw`（`.java/.yml/.properties/.imports/...` 全部覆盖）。
2. **Apply** — `rsync --delete` 把 `build/` 同步到 `mate-campusclaw/`，跳过 `scripts/sync-mate-exclude.txt` 中登记的 mate 侧独有路径（如 `assistant/config/`、`codingagent/channel/`）。
3. **Verify** — 在 `mate-campusclaw/` 跑 `mvn compile`（自动找 JDK 21，跳过 checkstyle/spotless）。

> 在 `mate-campusclaw/` 下手写新文件、且与 `modules/*` 没有对应关系时，记得把路径加进 `scripts/sync-mate-exclude.txt`，否则下次 `--delete` 会清掉它。`application.properties` 和 `application-assistant.yml` 是按环境手工维护的，脚本永远不动；只有 `schema.sql` 和 `META-INF/spring/*.imports` 会从 `modules/*` 同步过来。

#### pre-push 自动校验

仓库自带一个 `scripts/git-hooks/pre-push` 钩子，在 `git push` 前自动检查 `mate-campusclaw/` 是否与 `modules/*` 同步——不同步就拦住 push 并提示先跑同步脚本。每次新 clone 仓库后启用一次即可：

```bash
git config core.hooksPath scripts/git-hooks
```

只有当本次 push 的提交范围里**真的动了** `modules/`、`mate-campusclaw/` 或 `scripts/sync-mate*` 时，钩子才会跑校验（其他改动直接放行，不会拖慢 push）。如果你确认要先 push、稍后再补同步，可以临时绕过：

```bash
git push --no-verify
```

## 故障排查

### Maven 构建失败：JDK 版本不兼容

**现象**：`Unsupported class file major version` 或编译报错

**原因**：项目要求 JDK 21+

**解决**：

```bash
export JAVA_HOME=/path/to/jdk-21
./mvnw verify
```

### 启动脚本找不到 JDK 21

**现象**：`Error: JDK 21 not found`

**解决**：手动设置 `JAVA_HOME` 环境变量指向 JDK 21 安装目录，然后重新运行脚本。

### 终端显示乱码或无颜色

**现象**：TUI 界面渲染异常

**解决**：

- 确保终端支持 ANSI 转义序列
- 确认 `TERM` 环境变量不为 `dumb`

### API 调用报 401 / Unauthorized

**现象**：模型请求返回认证错误

**解决**：

- 确认对应供应商的环境变量已设置且值正确
- 运行 `echo $ANTHROPIC_API_KEY` 验证
- 也可通过 `--api-key` 参数直接传入

### 源码改了但启动脚本没有重新构建

**现象**：修改了 Java 代码但运行行为没变

**解决**：启动脚本会自动检测源码变更并重新构建。如果检测不准确，可手动强制：

```bash
./campusclaw.sh --rebuild -m glm-5
```
