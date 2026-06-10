# Pi-Mono 架构分析

> Pi-Mono 是一个 TypeScript AI Agent 工具链，提供统一 LLM API、交互式 Coding Agent、终端 UI 框架、Slack 集成和 GPU 部署管理能力。本文档从架构设计、模块功能、核心工作流三个维度进行深度分析。

---

## 一、Monorepo 结构与模块依赖

### 1.1 包总览

```
pi-mono/
├── packages/
│   ├── ai/              @mariozechner/pi-ai           # 统一 LLM API（底层）
│   ├── agent/           @mariozechner/pi-agent-core    # Agent 运行时（中层）
│   ├── tui/             @mariozechner/pi-tui           # 终端 UI 框架（中层）
│   ├── coding-agent/    @mariozechner/pi-coding-agent  # Coding Agent CLI（上层）
│   ├── mom/             @mariozechner/pi-mom           # Slack Bot（上层）
│   ├── web-ui/          @mariozechner/pi-web-ui        # Web UI 组件（上层）
│   └── pods/            @mariozechner/pi-pods          # GPU Pod 管理（上层）
```

所有包共享同一版本号（lockstep versioning），统一通过 `npm run build` 按依赖顺序构建。

### 1.2 依赖关系图

```
                    ┌──────────────┐
                    │   pi-ai      │  统一 LLM API
                    │ (无内部依赖)  │  Provider 抽象层
                    └──────┬───────┘
                           │
                 ┌─────────┴─────────┐
                 │                   │
        ┌────────▼───────┐  ┌───────▼────────┐
        │  pi-agent-core │  │    pi-tui      │  终端 UI 框架
        │   Agent 运行时  │  │  差量渲染引擎   │
        └────────┬───────┘  └───────┬────────┘
                 │                   │
        ┌────────┴───────────────────┴────────┐
        │                                     │
┌───────▼──────────┐              ┌───────────▼──────┐
│  pi-coding-agent  │◄─────────────│    pi-mom        │
│   Coding Agent   │              │   Slack Bot      │
└──────────────────┘              └──────────────────┘

        ┌──────────────────┐       ┌──────────────────┐
        │    pi-web-ui      │       │    pi-pods        │
        │   Web UI 组件    │       │  GPU Pod 管理    │
        └──────────────────┘       └──────────────────┘
```

**分层设计思想**：
- **基础层（pi-ai）**：纯 API 抽象，不依赖任何内部包，可独立使用
- **中间层（pi-agent-core + pi-tui）**：提供 Agent 运行时和 UI 渲染能力，仅依赖基础层
- **应用层（coding-agent / mom / web-ui / pods）**：面向用户的具体产品，组合中间层能力

---

## 二、各模块功能详解

### 2.1 pi-ai — 统一 LLM API

**核心职责**：屏蔽不同 LLM 厂商 API 差异，提供统一的调用接口。

**支持的 Provider**（20+）：
- Anthropic (Claude 系列)
- OpenAI (GPT 系列)
- Google (Gemini 系列)
- Mistral
- AWS Bedrock / Azure OpenAI
- GitHub Copilot
- Ollama (本地模型)

**关键文件**：
```
packages/ai/src/
├── types.ts              # 核心类型定义 (Model, Message, Tool, Context)
├── stream.ts             # 统一流式接口 AssistantMessageEventStream
├── api-registry.ts       # Provider 注册表，懒加载 Provider 模块
├── providers/
│   ├── anthropic.ts      # Anthropic 实现
│   ├── openai.ts         # OpenAI 实现
│   ├── google.ts         # Google 实现
│   └── ...
└── utils/
    ├── event-stream.ts   # 事件流实现
    └── ...
```

**核心类型体系**：

```typescript
// 模型定义 — 每个模型关联一个 Provider API 类型
interface Model<TApi> {
    id: string;                    // e.g., "claude-sonnet-4-20250514"
    name: string;                  // e.g., "Claude Sonnet 4"
    api: TApi;                     // Provider 特定的 API 实例
    provider: Provider;            // Provider 信息
    baseUrl: string;
    reasoning: boolean;            // 是否支持推理
    cost: {                        // 每 token 成本
        input: number;
        output: number;
        cacheRead: number;
        cacheWrite: number;
    };
}

// 上下文 — 发送给 LLM 的完整请求
interface Context {
    systemPrompt?: string;
    messages: Message[];
    tools?: Tool[];
}

// 工具定义 — 统一的工具描述格式
interface Tool {
    name: string;
    description: string;
    parameters: JsonSchema;        // JSON Schema 描述参数
}

// 消息类型
type Message = UserMessage | AssistantMessage;
interface UserMessage {
    role: "user";
    content: (TextContent | ImageContent)[];
}
interface AssistantMessage {
    role: "assistant";
    content: (TextContent | ThinkingContent | ToolCallContent)[];
    usage: Usage;                  // token 用量
}
```

**流式事件协议**：

```typescript
type AssistantMessageEvent =
    | { type: "message_start"; message: AssistantMessage }
    | { type: "text_delta"; text: string }
    | { type: "thinking_delta"; thinking: string }
    | { type: "toolcall_start"; toolCallId: string; toolName: string }
    | { type: "toolcall_delta"; toolCallId: string; argumentsDelta: string }
    | { type: "toolcall_end"; toolCallId: string; arguments: object }
    | { type: "message_end"; message: AssistantMessage }
    | { type: "error"; error: Error };
```

**Provider 适配原理**（以 Anthropic 为例）：
1. 将统一的 `Tool[]` 转换为 Anthropic 格式的 `Messages.Tool[]`
2. 调用 `anthropic.messages.stream()` 获取 SSE 流
3. 将 Anthropic 事件（`content_block_delta` 等）转换为统一的 `AssistantMessageEvent`
4. 最终产出统一的 `AssistantMessage`

### 2.2 pi-agent-core — Agent 运行时

**核心职责**：实现 Agent 循环，管理状态，协调工具执行。

**Agent 循环结构**（双层循环）：

```
外层循环 (follow-up loop)          内层循环 (tool-call loop)
┌─────────────────────────┐      ┌──────────────────────────┐
│ while (hasFollowUp) {   │      │ while (hasToolCalls) {   │
│   agent.turn()          │─────►│   1. 发送给 LLM          │
│ }                       │      │   2. 收到 tool_use       │
└─────────────────────────┘      │   3. 执行工具            │
                                 │   4. 结果返回 LLM        │
                                 │ }                        │
                                 └──────────────────────────┘
```

**关键文件**：
```
packages/agent/src/
├── agent.ts          # Agent 类 — 状态管理、prompt/steer/followUp API
├── agent-loop.ts     # AgentLoop 类 — 核心循环实现
├── state.ts          # MutableAgentState — 可变状态管理
├── types.ts          # AgentEvent, ToolCall 等核心类型
└── utils/
    ├── context.ts    # 上下文构建
    └── ...
```

**Agent 类核心 API**：

```typescript
class Agent {
    // 主入口 — 用户发送 prompt
    prompt(userMessage: string, images?: ImageContent[]): Promise<void>;

    // 中断当前执行，注入新消息（下一个 LLM turn 之前）
    steer(message: string): void;

    // 排队消息（当前 Agent 完成后执行）
    followUp(message: string): void;

    // 从当前 transcript 继续
    continue(): Promise<void>;

    // 事件订阅
    subscribe(listener: (event: AgentEvent) => void): () => void;
}
```

**消息队列机制**：
- `steer()` 消息优先级最高，在当前 assistant turn 结束后、下次 LLM 调用前注入
- `followUp()` 消息在 Agent 本该停止时触发新一轮循环
- 队列模式可配置为 `"all"`（处理全部）或 `"one-at-a-time"`（只取一条）

**Tool Call 执行流程**（`agent-loop.ts` 中的 `executeToolCalls()`）：

```
1. prepareToolCallArguments()    — 工具特定的参数预处理
2. validateToolArguments()       — JSON Schema 校验
3. beforeToolCall() hook         — 扩展点：可修改参数或阻止执行
4. executePreparedToolCall()     — 实际执行，支持流式更新
5. afterToolCall() hook          — 扩展点：可修改结果
```

支持 **顺序执行** 和 **并行执行** 两种模式。

**Stop Reason 处理**：

```typescript
// LLM 返回的停止原因
type StopReason = "end_turn" | "tool_use" | "max_tokens" | "stop_sequence";

// Agent 如何处理：
// end_turn      → 检查 follow-up 队列，有则继续，无则结束
// tool_use      → 执行工具，将结果加入消息，继续内层循环
// max_tokens    → 触发 compaction 或截断处理
// stop_sequence → 正常结束
```

### 2.3 pi-tui — 终端 UI 框架

**核心职责**：在终端中高效渲染 AI 对话界面。

**关键技术**：**差量渲染（Differential Rendering）**
- 只重绘发生变化的行，避免整屏刷新闪烁
- 使用 ANSI 转义序列定位光标
- 支持硬件光标定位（兼容 IME 输入法）

**组件体系**：

```
Component (抽象基类)
├── Container          # 容器，管理子组件布局
├── Text               # 纯文本渲染
├── Markdown           # Markdown 渲染（支持代码高亮、链接、引用等）
├── Input              # 文本输入框（支持自动补全）
├── Editor             # 代码编辑器（语法高亮）
├── SelectList         # 选择列表（模糊匹配）
└── Image              # 终端图片渲染（Kitty / iTerm2 协议）
```

**Overlay 系统**：支持模态对话框叠加在当前内容之上，用于确认操作、显示帮助等。

### 2.4 pi-coding-agent — Coding Agent CLI

**核心职责**：面向开发者的交互式编程助手，是整个项目的主要用户入口。

**内置工具**（`packages/coding-agent/src/core/tools/`）：

| 工具 | 文件 | 功能 |
|------|------|------|
| `read` | `read.ts` | 读取文件内容（支持截断大文件、读取 PDF、图片） |
| `write` | `write.ts` | 写入/创建文件（带 mutation queue） |
| `edit` | `edit.ts` | 精确字符串替换编辑 |
| `bash` | `bash.ts` | 执行 shell 命令（支持超时、沙箱、流式输出） |
| `grep` | `grep.ts` | 文件内容搜索（基于 ripgrep） |
| `find` | `find.ts` | 文件系统搜索（按文件名模式匹配） |
| `ls` | `ls.ts` | 目录列表 |

**Bash 工具的沙箱设计**（可插拔执行器）：

```typescript
interface BashOperations {
    exec: (
        command: string,
        cwd: string,
        options: {
            onData: (data: Buffer) => void;  // 流式输出回调
            signal?: AbortSignal;             // 支持中断
            timeout?: number;                 // 超时控制
            env?: NodeJS.ProcessEnv;          // 环境变量注入
        },
    ) => Promise<{ exitCode: number | null }>;
}
```

默认实现使用 Node.js `child_process`，可替换为远程执行器（如 SSH、Docker）。

**Session 管理**：

```
~/.pi/sessions/
├── {session-id}.jsonl       # 会话消息记录
└── ...
```

- 支持新建、继续、分叉（fork）、恢复（resume）会话
- 会话文件使用 JSONL 格式，每行一条 Entry
- Entry 类型：`SessionMessageEntry`（普通消息）、`CompactionEntry`（压缩摘要）、`BranchSummaryEntry`（分支摘要）

### 2.5 pi-mom — Slack 集成

通过 Slack Socket Mode 连接，将频道消息委托给 coding agent 处理，实现团队级的 AI 编程助手。每个 Slack 频道对应一个独立的 Agent Session。

### 2.6 pi-web-ui / pi-pods

- **pi-web-ui**：基于 Lit 的 Web Components，提供聊天界面、文档预览（PDF/DOCX/XLSX）
- **pi-pods**：vLLM GPU Pod 部署管理 CLI

---

## 三、核心工作流

### 3.1 端到端执行流：用户输入 → Agent 响应

```
用户输入 "帮我修复这个 bug"
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│  1. InteractiveMode 接收输入 (packages/coding-agent/src/modes/)  │
│     - TUI Input 组件捕获用户回车                                  │
│     - 判断是否为 slash command（/help, /compact 等）              │
│     - 展开 Skill 命令和 Prompt 模板                               │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. AgentSession.prompt() (packages/coding-agent/src/core/)      │
│     - 校验 model 和 API key                                      │
│     - 构建消息数组（历史消息 + 用户新消息）                        │
│     - 触发 before_agent_start 事件（扩展可修改 system prompt）     │
│     - 调用 Agent.prompt()                                        │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. AgentLoop — 核心循环 (packages/agent/src/agent-loop.ts)      │
│                                                                  │
│  ┌─ 构建上下文 ─────────────────────────────────────────────┐    │
│  │ - 组装 system prompt（默认指令 + 工具列表 + 技能 + 上下文）│    │
│  │ - 转换 AgentMessage[] → LLM Message[]                    │    │
│  │ - 注入可用工具的 JSON Schema                              │    │
│  │ - 解析 API key（处理过期 token 刷新）                      │    │
│  └──────────────────────────────────────────────────────────┘    │
│                       │                                          │
│                       ▼                                          │
│  ┌─ 调用 LLM ───────────────────────────────────────────────┐    │
│  │ - streamAssistantResponse()                              │    │
│  │ - 发送请求到 Provider（如 Anthropic API）                  │    │
│  │ - 流式接收事件：text_delta, thinking_delta, toolcall_*     │    │
│  │ - 事件通过 processEvents() 更新状态 + 通知监听者           │    │
│  │ - TUI 差量渲染实时更新终端                                 │    │
│  └──────────────────────────────────────────────────────────┘    │
│                       │                                          │
│              ┌────────┴────────┐                                │
│              │   Stop Reason?  │                                │
│              └──┬──────────┬───┘                                │
│         end_turn│          │tool_use                            │
│                 │          ▼                                    │
│                 │   ┌─ 执行工具 ──────────────────────────┐     │
│                 │   │ 1. beforeToolCall hook               │     │
│                 │   │ 2. executePreparedToolCall()        │     │
│                 │   │    例: bash → exec("grep -r ...")    │     │
│                 │   │    例: read → fs.readFile(...)       │     │
│                 │   │ 3. afterToolCall hook                │     │
│                 │   │ 4. 工具结果加入消息历史               │     │
│                 │   └──────────────┬──────────────────────┘     │
│                 │                  │                             │
│                 │                  └──── 继续内层循环 ─────►     │
│                 │                                                │
│                 ▼                                                │
│         ┌─ 检查 follow-up 队列 ─┐                                │
│         │ 有 → 继续外层循环     │                                │
│         │ 无 → Agent 结束       │                                │
│         └───────────────────────┘                                │
└─────────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. 后处理                                                        │
│     - 持久化会话到 JSONL 文件                                     │
│     - 检查是否需要 context compaction                             │
│     - 更新 token 使用统计                                         │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 System Prompt 构建流程

```typescript
buildSystemPrompt({
    selectedTools,        // 当前启用的工具列表
    toolSnippets,         // 工具的简短描述（用于注入到 system prompt）
    promptGuidelines,     // 额外的行为准则
    contextFiles,         // 项目上下文文件（如 CLAUDE.md、.cursorrules）
    skills,               // 可用的 Skill 列表
})
```

**System Prompt 组成部分**（按顺序拼接）：

```
┌──────────────────────────────┐
│  1. Default Prompt            │  内置行为指令（角色定义、工具使用规范等）
├──────────────────────────────┤
│  2. Tool Descriptions         │  每个工具的名称、描述、使用规范
├──────────────────────────────┤
│  3. Tool Guidelines           │  基于可用工具的行为准则
├──────────────────────────────┤
│  4. Skills (XML)              │  可用技能列表（XML 格式）
│  │  <available_skills>        │
│  │    <skill>                 │
│  │      <name>xxx</name>      │
│  │      <description>...</>   │
│  │    </skill>                │
│  │  </available_skills>       │
├──────────────────────────────┤
│  5. Context Files             │  项目级上下文（CLAUDE.md 等）
├──────────────────────────────┤
│  6. Metadata                  │  当前日期、工作目录
└──────────────────────────────┘
```

### 3.3 Context Compaction（上下文压缩）

**问题**：LLM 上下文窗口有限（如 200k tokens），长对话会超出限制。

**解决方案**：自动压缩旧对话，保留近期上下文。

**触发条件**：
- 当前 token 使用量 > `contextWindow - reserveTokens`

**压缩算法**：

```
1. shouldCompact()     — 判断是否需要压缩
       │
       ▼
2. findCutPoint()      — 从后往前找到安全切割点
   │  只能在特定消息类型切割：
   │  user / assistant / custom / bashExecution
   │
       ▼
3. 提取要压缩的消息段
       │
       ▼
4. 调用 LLM 生成摘要（使用另一个较小的模型以节省成本）
       │
       ▼
5. 构造 CompactionEntry：
   {
     summary: "之前的对话摘要...",
     compactionDetails: {
       readFiles: ["/path/to/file1", ...],    // 读过的文件
       modifiedFiles: ["/path/to/file2", ...], // 修改过的文件
     }
   }
       │
       ▼
6. 替换消息历史：旧消息 → CompactionEntry + 保留的近期消息
```

**文件追踪**：压缩时记录所有读/写过的文件路径，确保 Agent 不会"忘记"它看过什么文件。

### 3.4 Extension 系统

**设计模式**：Factory Pattern — 每个扩展是一个导出工厂函数的 TypeScript 模块。

```typescript
// 扩展定义示例
export default function(pi: ExtensionAPI) {
    // 注册自定义工具
    pi.registerTool({
        name: "deploy",
        description: "部署应用到生产环境",
        parameters: Type.Object({ service: Type.String() }),
        execute: async (id, params, signal, onUpdate, ctx) => {
            // 执行部署逻辑
            return { content: [{ type: "text", text: "部署成功" }] };
        },
    });

    // 注册 slash command
    pi.registerCommand("deploy", {
        description: "一键部署",
        execute: async (args) => { /* ... */ },
    });

    // 订阅生命周期事件
    pi.on("tool_call", async (event, ctx) => {
        if (event.toolName === "bash" && event.input.command.includes("rm -rf")) {
            return { block: true, reason: "危险操作已被阻止" };  // 阻止执行
        }
    });

    pi.on("tool_result", async (event, ctx) => {
        // 修改工具结果
        return { content: [{ type: "text", text: "过滤后的结果" }] };
    });
}
```

**可用的生命周期钩子**：

| 事件 | 触发时机 | 能力 |
|------|----------|------|
| `session_start` | 会话启动/加载/重载 | 初始化扩展状态 |
| `before_agent_start` | Agent 循环开始前 | 修改 system prompt |
| `context` | 每次 LLM 调用前 | 修改消息列表 |
| `before_provider_request` | API 请求发送前 | 替换请求 payload |
| `tool_call` | 工具执行前 | 修改参数 / 阻止执行 |
| `tool_result` | 工具执行后 | 修改结果 |
| `session_before_compact` | 上下文压缩前 | 自定义压缩逻辑 |
| `session_shutdown` | 进程退出 | 清理资源 |

### 3.5 Skill 系统

Skill 是可复用的 Markdown 指令文档，通过 XML 格式注入 System Prompt，让 Agent 在匹配任务时自动加载。

**发现路径**（优先级从低到高）：
1. `~/.pi/agent/skills/` — 全局技能
2. `{cwd}/.pi/skills/` — 项目技能
3. 设置中显式指定的路径

**Skill 文件格式**：
```markdown
---
name: my-skill
description: 用于特定任务的专项指令
disable-model-invocation: false
---

# 技能内容

当用户请求 XXX 时，按照以下步骤操作：
1. ...
2. ...
```

**工作方式**：Agent 不会直接读取 Skill 内容，而是在 System Prompt 中看到 `<available_skills>` 列表。当任务匹配某个 Skill 的描述时，Agent 使用 `read` 工具加载该 Skill 文件，将其作为上下文。

### 3.6 TUI 差量渲染流程

```
1. Component.render(width) → List<String>     // 每个组件生成 ANSI 文本行
2. TUI 合并所有组件行 → 全屏布局
3. 与上一帧对比（逐行 diff）
4. 只更新变化的行（使用 ANSI 光标定位码）
5. 移动硬件光标到输入行（兼容 IME）
```

**为什么不用全屏刷新？**
- 全屏刷新导致终端闪烁
- 大量输出时全屏刷新性能差
- 差量渲染只更新必要的行，用户体验更流畅

### 3.7 Provider 适配流程（以 Tool Call 为例）

```
统一 Tool 定义                Anthropic API 格式
┌──────────────────┐          ┌──────────────────────────┐
│ name: "bash"     │  转换为   │ name: "bash"             │
│ description: "..."│ ───────► │ description: "..."        │
│ parameters: {    │          │ input_schema: {           │
│   type: "object" │          │   type: "object",         │
│   properties: {  │          │   properties: {...},      │
│     command: {...}│          │   required: ["command"]   │
│   }              │          │ }                         │
│ }                │          │ }                         │
└──────────────────┘          └──────────────────────────┘
                                       │
                                       ▼
                              Anthropic API 返回
                              tool_use content block
                                       │
                                       ▼
                              统一 AssistantMessageEvent
                              { type: "toolcall_start", ... }
                              { type: "toolcall_delta", ... }
                              { type: "toolcall_end", ... }
```

**跨 Provider 兼容性处理**：
- OpenAI 的 `function` 调用 vs Anthropic 的 `tool_use` 统一映射
- 工具参数的 JSON Schema 格式差异自动适配
- 流式事件格式不同但统一为相同的 `AssistantMessageEvent` 类型

---

## 四、架构设计亮点（面试要点）

### 4.1 分层解耦
- `pi-ai` 作为纯 API 层，无业务依赖，可被任何 AI 应用复用
- `pi-agent-core` 只依赖 `pi-ai`，不关心 UI 或具体工具
- 应用层自由组合，coding-agent 和 Slack bot 共享 Agent 能力

### 4.2 事件驱动架构
- Agent 循环通过事件通知状态变化
- Extension 系统通过事件钩子实现无侵入扩展
- TUI 通过监听事件实现实时渲染
- 解耦了核心逻辑与 UI/扩展

### 4.3 流式优先设计
- 所有 LLM 调用都是流式的，逐 token 处理
- 工具执行支持流式输出（如 bash 命令的实时输出）
- TUI 差量渲染配合流式事件实现流畅体验
- 用户可在任何时刻通过 `steer()` 中断和重定向

### 4.4 可插拔工具系统
- 工具通过 JSON Schema 自描述，LLM 可理解工具能力
- 执行器可替换（本地 bash / SSH / Docker / MCP）
- Extension 可注册自定义工具，无需修改核心代码
- before/after hook 支持拦截、修改、阻止工具调用

### 4.5 上下文管理策略
- 自动 compaction 解决上下文窗口限制
- 文件追踪确保压缩后不丢失关键信息
- 多级存储（全局 / 项目 / 会话）实现配置分层
- Session fork 支持实验性探索而不影响主对话

### 4.6 多 Transport 支持
- 同一个 Agent 可对接 TUI（终端）、Web UI、Slack 等不同交互方式
- 通过 Extension API 抽象 UI 操作
- Agent 核心不感知具体的传输层
