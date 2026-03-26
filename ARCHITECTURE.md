# Pi-Mono 代码架构

## 概览

Pi-Mono 是一个基于 TypeScript 的 monorepo，提供多 LLM 提供商支持的 AI 编码代理平台。包含终端 UI、Web UI、Slack 机器人等多种交互方式。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | TypeScript (ES2022, Node16 modules) |
| 包管理 | npm workspaces |
| 构建 | tsgo |
| 测试 | Vitest |
| Lint/格式化 | Biome |
| 运行时 | Node.js >= 20, Bun (用于构建独立二进制) |
| AI SDK | OpenAI, Anthropic, Google Gemini, Mistral, AWS Bedrock |

## 顶层目录结构

```
pi-mono/
├── packages/              # 所有包
│   ├── ai/                # 统一 LLM API
│   ├── agent/             # 代理运行时核心
│   ├── coding-agent/      # 编码代理 CLI（主应用）
│   ├── mom/               # Slack 机器人
│   ├── pods/              # vLLM GPU Pod 管理
│   ├── tui/               # 终端 UI 库
│   └── web-ui/            # Web UI 组件
├── scripts/               # 构建和工具脚本
├── .pi/                   # 用户配置/扩展
├── package.json           # 根 workspace 配置
├── tsconfig.json          # TypeScript 配置
├── biome.json             # Lint/格式化规则
└── test.sh                # 测试运行脚本
```

## 包依赖关系

```
pi-coding-agent ─────┬── pi-agent-core ── pi-ai
                     ├── pi-tui
                     └── pi-ai

pi-mom ──────────────┬── pi-agent-core ── pi-ai
                     ├── pi-coding-agent
                     └── pi-ai

pi-pods ─────────────── pi-agent-core

pi-web-ui ────────────┬── pi-ai
                      └── pi-tui
```

## 各包详情

### `@mariozechner/pi-ai` — 统一多 LLM 提供商 API

> packages/ai/

核心 AI 抽象层，为所有上层提供统一的 LLM 调用接口。

| 目录/文件 | 职责 |
|-----------|------|
| `src/index.ts` | 主入口，导出公共 API |
| `src/providers/` | 各 LLM 提供商适配器 |
| `src/models/` | 模型定义与发现 |
| `src/stream/` | 流式响应处理 |
| `src/utils/` | 工具函数 |
| `src/cli.ts` | CLI 入口 (`pi-ai`) |

**支持的提供商**: OpenAI, Anthropic, Google Gemini, Mistral, AWS Bedrock

**关键特性**:
- 统一的 API 接口，屏蔽提供商差异
- 自动模型发现
- OAuth 认证支持
- 流式响应处理

---

### `@mariozechner/pi-agent-core` — 代理运行时核心

> packages/agent/

通用代理框架，提供状态管理、工具调用和传输抽象。

| 目录/文件 | 职责 |
|-----------|------|
| `src/index.ts` | 主入口 |
| `src/agent/` | 代理核心逻辑 |
| `src/agent-loop/` | 代理消息循环 |
| `src/proxy/` | 代理/转发 |
| `src/types/` | 类型定义 |

**关键特性**:
- 状态管理与会话维护
- 工具调用框架（Tool Calling）
- 附件支持
- 传输层抽象（Transport Abstraction）

---

### `@mariozechner/pi-coding-agent` — 编码代理 CLI（主应用）

> packages/coding-agent/

交互式编码代理命令行工具，是整个平台的主要用户入口。

| 目录/文件 | 职责 |
|-----------|------|
| `src/cli.ts` | CLI 入口 (`pi`) |
| `src/main.ts` | 主程序逻辑 |
| `src/core/` | 核心引擎 |
| `src/modes/` | 运行模式（交互式/非交互式） |
| `src/utils/` | 工具函数 |
| `src/cli/` | CLI 相关逻辑 |

**内置工具**:
- 文件读取/写入/编辑
- Bash 命令执行
- Git 操作
- Web 搜索
- 图像处理

**关键特性**:
- 会话管理与历史记录
- 扩展系统（Extensions）
- JSONL 会话导入/导出
- 多种运行模式
- 支持构建为独立二进制（via Bun）

---

### `@mariozechner/pi-tui` — 终端 UI 库

> packages/tui/

高性能终端 UI 组件库，支持差量渲染。

| 目录/文件 | 职责 |
|-----------|------|
| `src/index.ts` | 主入口 |
| `src/components/` | UI 组件 |
| `src/diff/` | Diff 显示组件 |
| `src/markdown/` | Markdown 渲染 |

**关键特性**:
- 差量渲染优化性能
- Markdown 渲染支持
- 可选原生扩展（koffi）

---

### `@mariozechner/pi-mom` — Slack 机器人

> packages/mom/

将 Slack 消息委托给 pi 编码代理的 Slack Bot。

| 目录/文件 | 职责 |
|-----------|------|
| `src/main.ts` | 主入口 |
| `src/slack/` | Slack 集成（Socket Mode + Web API） |
| `src/handlers/` | 消息处理器 |

**关键特性**:
- Slack Socket Mode 和 Web API 支持
- 消息委托到编码代理
- 定时任务（Cron）支持

---

### `@mariozechner/pi-pods` — GPU Pod 管理 CLI

> packages/pods/

管理 vLLM 部署的 GPU Pod 命令行工具。

| 目录/文件 | 职责 |
|-----------|------|
| `src/cli.ts` | CLI 入口 (`pi-pods`) |
| `src/models/` | 数据模型 |
| `src/scripts/` | 部署脚本 |

---

### `@mariozechner/pi-web-ui` — Web UI 组件

> packages/web-ui/

可复用的 AI 聊天界面 Web 组件。

| 目录/文件 | 职责 |
|-----------|------|
| `src/index.ts` | 主入口 |
| `src/components/` | Web Components |
| `src/example/` | 示例应用 |

**关键特性**:
- 基于 mini-lit/Lit 的 Web Components
- Tailwind CSS 样式
- 文件预览（PDF, DOCX, 图片）
- Ollama / LM Studio 集成

---

## 架构模式

### 1. 模块化 Monorepo

每个包职责单一，可独立发布和维护。包之间通过明确的依赖关系组织。

### 2. 代理架构（Agent Architecture）

```
用户输入 → Transport → Agent Loop → LLM Provider
                ↑              ↓
            Tool Framework ← Tool Response
```

核心代理循环：接收输入 → 调用 LLM → 执行工具 → 返回结果 → 循环

### 3. 提供商抽象（Provider Abstraction）

统一接口屏蔽底层 LLM 提供商差异，支持自动模型发现和认证。

### 4. 多界面支持

- **TUI** — 终端交互（CLI 模式）
- **Web UI** — 浏览器交互（Web Components）
- **Slack Bot** — 团队协作（消息委托）
- **Standalone Binary** — 独立分发

### 5. 扩展系统

编码代理支持丰富的扩展 API：
- 自定义消息渲染器
- 自定义工具提供者
- UI 主题定制

## 构建顺序

```
tui → ai → agent → coding-agent → mom → web-ui → pods
```

## 常用命令

```bash
npm install          # 安装所有依赖
npm run build        # 构建所有包
npm run check        # Lint + 格式化 + 类型检查
npm run dev          # 开发模式运行
npm test             # 运行测试
```
