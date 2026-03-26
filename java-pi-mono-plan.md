# Java Pi-Mono: 基于 Claude Code Agent Teams 的自动化开发方案

## Context

用 Claude Code Agent Teams（实验性功能）自动化开发 Java 版 pi-mono。代码放在 `/Users/z/pi-mono-java/`。

---

## 一、Agent Teams 配置

### 启用

```json
// /Users/z/pi-mono-java/.claude/settings.json
{
  "env": {
    "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"
  }
}
```

### 显示模式

推荐 tmux 分屏（`brew install tmux`），可以同时看到所有 teammate 的工作状态。

---

## 二、Agent 角色与职责（充实版）

### Architect Teammate

**不只是画类图，而是完整的软件设计师。**

每个需求的设计输出包含以下章节：

| 设计维度 | 输出内容 | 写入文件 |
|---|---|---|
| 需求分析 | 用户故事、验收标准、边界条件、非功能约束 | `designs/req-{id}-design.md` |
| 业务流程 | 核心流程描述（输入→处理→输出）、异常流程、状态转换 | 同上 |
| 数据设计 | Java 类/record/sealed interface 定义、字段类型、约束、序列化格式 | 同上 |
| 接口设计 | public API 签名（方法名、参数、返回值、异常）、内部模块间接口 | 同上 |
| 技术方案 | 使用的库、设计模式、线程模型、性能考量 | 同上 |
| 非功能设计 | 错误处理策略、日志规范、配置方式、兼容性要求 | 同上 |
| 测试策略 | 测试场景列表（单元/集成/接口/功能）、mock 策略、测试数据 | 同上 |
| TS 参考映射 | TS 文件路径 + 行号 → Java 类的对应关系 | 同上 |

**Architect 的工具**: read, grep, find, ls（只读，不写代码）

**Architect 的提示词要点**:
```
你是架构设计师。对于需求 req-{id}，你需要：
1. 读取 /Users/z/pi-mono/ 下的 TypeScript 参考代码，理解原始实现
2. 分析需求的业务流程（输入是什么、处理逻辑是什么、输出是什么）
3. 设计 Java 类结构（用 records/sealed interfaces 替代 TS union types）
4. 设计 public API 签名（方法名、参数、返回值、异常声明）
5. 识别外部依赖（Maven 坐标）
6. 列出错误处理策略和边界条件
7. 列出可验证的测试场景（包括正常流程和异常流程）
8. 将设计写入 .pi-dev/designs/req-{id}-design.md
```

### Developer Teammate

**不只是翻译代码，而是能编译运行的实现者。**

| 职责 | 具体内容 |
|---|---|
| 实现代码 | 根据设计文档写 Java 源码 |
| 编译验证 | 每写完一个类执行 `./gradlew compileJava`，确认无编译错误 |
| Smoke 验证 | 写一个 `main()` 方法或简单的集成点验证代码能跑通 |
| 依赖管理 | 在 `build.gradle.kts` 中添加需要的库 |
| 实现日志 | 记录实现过程中的决策和偏差 |

**Developer 的工具**: read, write, edit, bash, grep, find

**Developer 的提示词要点**:
```
你是 Java 开发者。根据 .pi-dev/designs/req-{id}-design.md 实现代码。
1. 严格按照设计文档的类结构和 API 签名实现
2. 每写完一个类运行 ./gradlew compileJava 验证编译
3. 可以用 read 查看 /Users/z/pi-mono/ 下的 TS 参考代码
4. 如果设计有遗漏或不合理，给 Architect 发消息讨论
5. 实现完成后写一个简单的 main() 验证核心流程可运行
6. 更新 .pi-dev/implementations/req-{id}-impl.md
```

### Tester Teammate

**不只是写 assert，而是真正运行验证的测试工程师。**

| 测试层级 | 做什么 | 怎么验证 |
|---|---|---|
| 单元测试 | 测试单个类/方法的逻辑正确性 | JUnit 5 + Mockito |
| 集成测试 | 测试模块内部多个类的协作 | JUnit 5，用真实对象（非 mock） |
| 接口测试 | 测试 public API 的契约是否符合设计 | 验证方法签名、参数校验、异常抛出 |
| 功能测试 | 端到端运行验证 | 执行 `main()` 或集成脚本，检查输出 |
| 回归测试 | 验证已有模块未被破坏 | `./gradlew test` 全量运行 |

**具体验证手段**（不只是单元测试）：

1. **编译测试**: `./gradlew compileJava compileTestJava` — 确认源码和测试代码都能编译
2. **单元测试**: `./gradlew test --tests "com.mariozechner.pi.ai.*"` — 运行指定模块测试
3. **集成测试**: 对于 LLM Provider，用 mock HTTP server（WireMock）模拟真实 API 响应
4. **接口契约测试**: 验证 public 方法的入参校验、返回类型、异常类型
5. **端到端运行**: 对于 CLI 模块，实际执行 `java -jar` 验证命令行输出
6. **全量回归**: `./gradlew test` 确保新代码不破坏已有功能
7. **代码质量**: `./gradlew check` 运行 Checkstyle + SpotBugs

**Tester 发现问题后的处理**:
- 编译错误 → 直接给 Developer 发消息，附上错误信息
- 测试失败 → 在 `.pi-dev/bugs/` 创建 bug 单，Developer 修复后重新测试
- 设计缺陷 → 给 Architect 发消息讨论，可能需要更新设计文档

**Tester 的工具**: read, write, edit, bash, grep, find

---

## 三、Java 技术栈

| 层 | 方案 |
|---|---|
| Monorepo | Gradle multi-module (Kotlin DSL) |
| LLM 集成 | 自定义接口 + 官方 SDK |
| Agent 框架 | 自定义实现 |
| CLI | Picocli |
| TUI | JLine 3 + Lanterna |
| Web UI | Spring Boot + HTMX |
| 测试 | JUnit 5 + Mockito + WireMock + Testcontainers |
| JSON | Jackson |
| Schema 校验 | JSON Schema Validator (everit) |
| HTTP | Java 11 HttpClient + OkHttp |
| 异步/流 | CompletableFuture + Flow.Publisher |
| 代码质量 | Checkstyle + SpotBugs + JaCoCo |

---

## 四、细粒度需求拆分

### 模块 1: `modules/ai`（LLM 提供商抽象层）

对应 TS: `packages/ai/`（~25,000 行）

#### Phase 1: 类型基础（无外部依赖，纯定义）

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| AI-001 | Model 元数据定义 | `Model<TApi>` record，包含 id/name/api/provider/cost/contextWindow/maxTokens 等 13 个字段。Java 用 sealed interface + records 替代 TS 泛型联合类型 | `types.ts:1-50` | 小 |
| AI-002 | Message 类型体系 | `UserMessage`, `AssistantMessage`, `ToolResultMessage` sealed interface。包含 `TextContent`, `ThinkingContent`, `ImageContent`, `ToolCall` 4 种 content block | `types.ts:50-200` | 中 |
| AI-003 | 流式事件类型 | 13 种 `AssistantMessageEvent` 类型：start/text_start/text_delta/text_end/thinking_*/toolcall_*/done/error | `types.ts:200-338` | 中 |
| AI-004 | 配置类型定义 | `ThinkingLevel` 枚举(5 值)、`CacheRetention`、`Transport`、`StreamOptions`(10+ 字段)、`OpenAICompletionsCompat`(15 个兼容标志) | `types.ts` 全文 | 小 |
| AI-005 | Usage 与 Cost 计算 | `Usage` record(input/output/cacheRead/cacheWrite/totalTokens)、`Cost` record、`calculateCost(model, usage)` 方法 | `models.ts:50-78` | 小 |

#### Phase 2: 核心基础设施

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| AI-006 | EventStream 流式框架 | `EventStream<T, R>` 泛型异步流。Java 用 `Flow.Publisher` + `CompletableFuture<R>` 实现。支持 queue-based 异步迭代和 `.result()` 同步等待 | `stream.ts`, `utils/event-stream.ts` | 大 |
| AI-007 | API Provider 注册表 | 插件系统：`registerApiProvider(provider)`、`getApiProvider(api)`、`unregisterApiProviders(sourceId)`。每个 provider 实现 `stream()` 和 `streamSimple()` | `api-registry.ts` (99行) | 小 |
| AI-008 | Model 注册表 | `getModel(provider, modelId)` 类型安全查找、`getProviders()`、`getModels(provider)`、`modelsAreEqual()`、`supportsXhigh()` | `models.ts` (78行) | 小 |
| AI-009 | 环境变量 API Key 解析 | 12 个 provider 的 env var 映射（OPENAI_API_KEY 等）。特殊处理：Vertex ADC、Bedrock 6 种凭证源、Copilot 多 token | `env-api-keys.ts` (134行) | 中 |
| AI-010 | 工具调用校验 | Jackson Schema → 运行时校验。`validateToolCall(tools, toolCall)` 带类型强制转换和详细错误路径 | `utils/validation.ts` (85行) | 中 |

#### Phase 3: 消息转换与公共逻辑

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| AI-011 | 消息转换管道 | same-model vs cross-model 检测、thinking block 转换（带签名 vs 纯文本）、tool call ID 标准化、synthetic tool result 插入、error/abort 消息过滤 | `providers/transform-messages.ts` (172行) | 大 |
| AI-012 | Partial JSON 解析 | 流式场景下的不完整 JSON 解析。Java 可用 Jackson Streaming API 或移植 partial-json 逻辑 | `utils/json-parse.ts` (28行) | 中 |
| AI-013 | 工具辅助函数 | Unicode 清理（surrogate pair）、短 hash 生成（Mistral tool ID）、上下文溢出检测 | `utils/sanitize-unicode.ts`, `utils/hash.ts`, `utils/overflow.ts` | 小 |

#### Phase 4: Provider 实现（每个 provider 一个需求）

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| AI-014 | Anthropic Provider | Claude Messages API。adaptive thinking (Opus 4.6/Sonnet 4.6 effort 级别)、budget-based thinking、OAuth 检测（sk-ant-oat token）、prompt caching（ephemeral + 1h TTL）、tool name canonicalization、redacted thinking | `providers/anthropic.ts` (900行) | 大 |
| AI-015 | OpenAI Completions Provider | Chat Completions API + 25+ 兼容 provider。15 个兼容标志自动检测、多种 reasoning 格式、provider routing (OpenRouter/Vercel)、tool call ID 标准化（40 字符限制） | `providers/openai-completions.ts` (860行) | 大 |
| AI-016 | OpenAI Responses Provider | Responses API (GPT-5, Codex)。session-based caching (24h)、reasoning effort (minimal→xhigh)、service tiers (flex/priority)。依赖 shared processing | `providers/openai-responses.ts` (262行) + `openai-responses-shared.ts` (507行) | 大 |
| AI-017 | Google Generative AI Provider | 直接 Gemini API。thinking levels (LOW/HIGH for Pro, MINIMAL~HIGH for Flash)、token budgets (Gemini 2.5)、thought signatures | `providers/google.ts` (458行) | 中 |
| AI-018 | Google Vertex AI Provider | Vertex AI + ADC/API key 认证。project/location 配置、与 Generative AI 共享 thinking 逻辑 | `providers/google-vertex.ts` (523行) | 中 |
| AI-019 | Google Gemini CLI Provider | 最复杂 provider。3 endpoint fallback (daily→autopush→prod)、empty stream retry (指数退避)、rate limit delay 解析（4 种格式）、Antigravity 模式 | `providers/google-gemini-cli.ts` (970行) | 极大 |
| AI-020 | AWS Bedrock Provider | Converse API。6 种凭证源、HTTP proxy、HTTP/1.1 fallback、Claude adaptive thinking、cache points (1h TTL)、image base64→byte[] 转换 | `providers/amazon-bedrock.ts` (770行) | 大 |
| AI-021 | Mistral Provider | Conversations API。tool call ID hash 碰撞解决（9 字符）、session caching (x-affinity header)、promptMode: "reasoning" | `providers/mistral.ts` (585行) | 中 |

#### Phase 5: OAuth 与高级功能

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| AI-022 | OAuth 框架 | `OAuthProviderInterface`（login/refresh/getApiKey 等 6 方法）、provider 注册表、auto-refresh on expiry | `utils/oauth/` 目录 | 中 |
| AI-023 | Anthropic OAuth | PKCE flow、localhost:63724 callback server、sk-ant-oat token | `utils/oauth/anthropic.ts` | 中 |
| AI-024 | GitHub Copilot OAuth | Device code flow、轮询 GitHub activation、域名标准化 (.com/.cn) | `utils/oauth/github-copilot.ts` | 中 |
| AI-025 | 模型目录生成 | 从 TS models.generated.ts (347KB) 转换为 Java 静态数据。包含所有 provider 的 model 定义和定价 | `models.generated.ts` | 中 |
| AI-026 | AI 模块公共 API 与集成测试 | `stream()`、`complete()`、`streamSimple()`、`completeSimple()` 4 个入口函数。用 WireMock 模拟 provider 响应做集成测试 | `index.ts` | 大 |

### 模块 2: `modules/agent-core`（Agent 运行时核心）

对应 TS: `packages/agent/`（~2,000 行核心逻辑）

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| AC-001 | AgentState 不可变状态 | systemPrompt, model, thinkingLevel, tools, messages, isStreaming, streamMessage, pendingToolCalls, error。Java 用 record + Builder | `types.ts` (310行) | 中 |
| AC-002 | AgentTool 接口 | 扩展 ai 模块的 Tool 类型，增加 label、execute() 返回 `AgentToolResult<TDetails>`、onUpdate callback 支持流式进度 | `types.ts` | 小 |
| AC-003 | AgentMessage 类型系统 | `Message | CustomAgentMessages` 联合类型。Java 用 sealed interface，支持自定义消息类型扩展 | `types.ts` | 中 |
| AC-004 | Agent Event 体系 | 11 种事件：agent_start/end, turn_start/end, message_start/update/end, tool_execution_start/update/end | `types.ts` | 小 |
| AC-005 | Agent Loop 核心循环 | 核心：prompt → streamLLM → extractToolCalls → executeTools → repeat。支持 steering（中断注入）和 follow-up（队列后续消息）。context transform hook | `agent-loop.ts` (616行) | 极大 |
| AC-006 | Tool 执行器 | prepareToolCall → executePreparedToolCall → finalizeExecutedToolCall 三阶段。支持顺序和并行执行模式 | `agent-loop.ts` 中的 executeToolCalls | 大 |
| AC-007 | Hook 系统 | beforeToolCall(可 block 执行)、afterToolCall(可覆盖结果)。接收 AgentContext(systemPrompt, messages, tools) | `types.ts` hook 定义 | 中 |
| AC-008 | Agent 类封装 | 包装 AgentState + Loop。提供 prompt(), continue(), steer(), followUp(), abort(), waitForIdle()。管理 steering/followUp 队列 | `agent.ts` (613行) | 大 |
| AC-009 | Transport 抽象（Proxy） | 代理流式函数：剥离 partial 字段减少带宽、客户端重建 partial message、SSE 协议、Bearer token 认证 | `proxy.ts` (340行) | 中 |
| AC-010 | Agent-core 集成测试 | mock LLM provider + 真实 tool 执行。验证完整 agent loop：多轮对话、tool calling、steering 中断、abort | 参考 `agent-loop.test.ts`, `agent.test.ts` | 大 |

### 模块 3: `modules/coding-agent-cli`（CLI 主应用）

对应 TS: `packages/coding-agent/`（~60,000 行）

#### Phase 1: CLI 骨架与配置

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| CA-001 | CLI 参数解析 | Picocli 实现 46 个参数字段。核心：provider, model, thinking, session 管理, 资源路径, 输出模式 | `cli/args.ts` (~200行) | 中 |
| CA-002 | 配置管理 (SettingsManager) | global (~/.pi/agent/settings.json) + project (.pi/settings.json) 双层合并。50+ 字段，deep merge。文件锁并发安全 | `core/settings-manager.ts` (953行) | 大 |
| CA-003 | 资源发现 (ResourceLoader) | CLAUDE.md 向上遍历发现、skill/extension/theme/prompt 路径汇总、extension 的 resources_discover hook | `core/resource-loader.ts` (868行) | 大 |
| CA-004 | Model 注册与解析 | API key 从 env/auth.json/OAuth 解析、model fuzzy match、thinking level clamping、fallback chain | `core/model-registry.ts` (718行) + `core/model-resolver.ts` (628行) | 大 |

#### Phase 2: Session 管理

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| CA-005 | Session JSONL 存储 | Header + 10 种 entry 类型。append-only + fsync。tree 结构（id + parentId）支持分支 | `core/session-manager.ts` (1410行) | 极大 |
| CA-006 | Session 分支与 Fork | fork(entryId) 从指定节点分支、getTree() 构建树、getBranch(leafId) 线性路径、buildContext(leafId) 转 AgentMessage[] | `core/session-manager.ts` | 大 |
| CA-007 | Session 迁移 | v1/v2 → v3 格式升级。runMigrations() | `core/session-manager.ts` | 中 |
| CA-008 | Compaction（上下文压缩） | 溢出检测、旧消息摘要生成（LLM 调用）、文件追踪（read/modified files）、auto-compaction 队列 + 重试 | `core/compaction/` (4 文件, 816行主逻辑) | 大 |

#### Phase 3: 内置工具

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| CA-009 | bash 工具 | spawn shell、stdout/stderr 流式输出到 temp file、截断(10000 行/512KB)、timeout、Operations 接口 | `core/tools/bash.ts` (10528行) | 极大 |
| CA-010 | read 工具 | 文件读取 + offset/limit、图片检测(MIME type) → ImageContent、自动 resize (2000x2000)、文本截断 | `core/tools/read.ts` (8505行) | 大 |
| CA-011 | edit 工具 | 精确匹配替换 + fuzzy fallback 建议、行尾保留(CRLF/LF)、BOM 处理、unified diff 生成 | `core/tools/edit.ts` (6981行) | 大 |
| CA-012 | write 工具 | 写文件 + 自动创建父目录 + UTF-8 | `core/tools/write.ts` (3372行) | 小 |
| CA-013 | grep 工具 | ripgrep 模式匹配、output_mode(content/files/count)、glob/type 过滤、context lines (-A/-B/-C)、multiline | `core/tools/grep.ts` (10440行) | 大 |
| CA-014 | find 和 ls 工具 | glob 文件发现、gitignore 支持、size/time 过滤 | `core/tools/find.ts` + `ls.ts` | 中 |

#### Phase 4: 扩展系统

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| CA-015 | Extension API 定义 | 29+ 事件类型 hook、registerTool/registerCommand/registerShortcut、runtime actions (sendMessage, setModel 等) | `core/extensions/types.ts` (1411行) | 极大 |
| CA-016 | Extension Loader | 动态加载 .java/.class 扩展、目录扫描、错误隔离 | `core/extensions/loader.ts` (545行) | 大 |
| CA-017 | Extension Runner | 生命周期管理、事件分发（顺序）、结果合并、error isolation、context 绑定 | `core/extensions/runner.ts` (908行) | 大 |
| CA-018 | Skill 系统 | SKILL.md 发现（递归扫描）、frontmatter 解析（name/description/disable-model-invocation）、gitignore 支持 | `core/skills.ts` (483行) | 中 |

#### Phase 5: 运行模式

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| CA-019 | AgentSession 核心 | 包装 Agent + SessionManager + SettingsManager + ModelRegistry。事件 JSONL 持久化、tool 管理、compaction 编排、model cycling、session 切换/分支 | `core/agent-session.ts` (3182行) | 极大 |
| CA-020 | Print 模式 | 单次执行输出最终文本、无交互 | `modes/print-mode.ts` | 小 |
| CA-021 | RPC 模式 | JSON stdin/stdout 协议、command 分发(prompt/abort/get_state/shutdown)、event streaming JSONL | `modes/rpc/rpc-mode.ts` (638行) | 大 |
| CA-022 | Interactive 模式（基础） | TUI 渲染、编辑器输入、消息显示、快捷键。初版可以简化，不做完整 TUI | `modes/interactive/` (4556行) | 极大 |

#### Phase 6: 集成与验证

| ID | 需求名称 | 描述 | TS 参考文件 | 预估复杂度 |
|---|---|---|---|---|
| CA-023 | CLI 端到端集成测试 | 用 print 模式验证：`java -jar pi.jar -p "hello"` 能调用 LLM 返回结果。WireMock 模拟 API | 参考 TS e2e tests | 大 |
| CA-024 | Session 持久化测试 | 创建 session → 写入消息 → 关闭 → 重新加载 → 验证完整性。包括 fork/branch 场景 | 参考 session-manager tests | 中 |
| CA-025 | Tool 功能测试 | 每个内置工具的真实文件系统操作测试（非 mock）：读文件、写文件、编辑文件、执行命令 | 参考 tools tests | 中 |

---

## 五、需求优先级排序（建议执行顺序）

### 第一批（核心类型 + 基础设施）— 无外部依赖

```
AI-001 → AI-002 → AI-003 → AI-004 → AI-005
→ AI-006 → AI-007 → AI-008 → AI-013
```

可以并行：AI-001~AI-005 是纯类型定义，互相独立。

### 第二批（消息转换 + 校验）

```
AI-009 → AI-010 → AI-011 → AI-012
```

### 第三批（第一个 Provider + 集成验证）

```
AI-014 (Anthropic) → AI-026 (公共 API + 集成测试)
```

先做 Anthropic 是因为它是最常用的 provider，验证完基础设施后再做其他 provider。

### 第四批（其他 Providers）— 可并行

```
AI-015 (OpenAI) | AI-017 (Google) | AI-020 (Bedrock) | AI-021 (Mistral)
→ AI-016 (OpenAI Responses) | AI-018 (Vertex) | AI-019 (Gemini CLI)
```

### 第五批（OAuth + 模型目录）

```
AI-022 → AI-023 | AI-024
→ AI-025
```

### 第六批（Agent Core）

```
AC-001 → AC-002 → AC-003 → AC-004
→ AC-005 → AC-006 → AC-007
→ AC-008 → AC-009 → AC-010
```

### 第七批（CLI 骨架）

```
CA-001 → CA-002 → CA-003 → CA-004
```

### 第八批（Session + Tools）

```
CA-005 → CA-006 → CA-007 → CA-008
CA-009 | CA-010 | CA-011 | CA-012 | CA-013 | CA-014（可并行）
```

### 第九批（扩展系统 + 运行模式）

```
CA-015 → CA-016 → CA-017 → CA-018
CA-019 → CA-020 → CA-021 → CA-022
```

### 第十批（端到端验证）

```
CA-023 → CA-024 → CA-025
```

---

## 六、测试策略详设

### 每个需求的 Tester 必须执行的验证

| 验证步骤 | 命令/方法 | 失败处理 |
|---|---|---|
| 1. 编译检查 | `./gradlew compileJava compileTestJava` | 给 Developer 发消息 |
| 2. 单元测试 | `./gradlew test --tests "包名.*"` | 创建 bug 单 |
| 3. 集成测试 | WireMock mock 外部 API 的测试类 | 创建 bug 单 |
| 4. 接口契约 | 验证 public method 签名、异常声明匹配设计文档 | 给 Architect 发消息 |
| 5. 全量回归 | `./gradlew test` | 给 Developer 发消息 |
| 6. 代码质量 | `./gradlew check` (Checkstyle + SpotBugs) | 创建 bug 单 |
| 7. 端到端（如适用） | 执行 main() 或 java -jar 验证输出 | 创建 bug 单 |

### 特定模块的测试重点

**AI 模块 Provider 测试**:
- 用 WireMock 启动 mock HTTP server，模拟真实 API 的 SSE/JSON 响应
- 验证 streaming 事件序列正确（start → deltas → end → done）
- 验证 tool calling 的请求格式和响应解析
- 验证错误处理（429 rate limit、500 server error、网络超时）
- 验证 cost 计算正确

**Agent Core 测试**:
- 用 mock provider 测试完整 agent loop
- 验证多轮对话：user → assistant → tool call → tool result → assistant
- 验证 steering 中断：tool 执行期间注入消息
- 验证 abort：中途取消操作

**CLI Tools 测试**:
- 在真实临时目录中执行文件操作（不 mock 文件系统）
- bash 工具：执行真实 shell 命令，验证输出截断
- read 工具：读取真实文件，验证 offset/limit
- edit 工具：编辑真实文件，验证 diff 生成

**Session 测试**:
- 创建、写入、关闭、重新打开 session，验证数据完整性
- Fork 场景：从中间节点分支，验证两个分支独立
- Compaction：模拟上下文溢出，验证摘要生成

---

## 七、上下文管理

### Markdown 状态文件

```
.pi-dev/
├── project-state.md              # Lead 维护，全局进度 <5k tokens
├── requirements/                  # 需求规格
│   ├── AI-001-model-definition.md
│   └── ...
├── designs/                       # Architect 输出
│   ├── AI-001-design.md
│   └── ...
├── implementations/               # Developer 日志
│   ├── AI-001-impl.md
│   └── ...
├── tests/                         # Tester 报告
│   ├── AI-001-tests.md
│   └── ...
├── bugs/                          # 失败 bug 单
└── templates/                     # 文档模板
    ├── requirement.md
    ├── design.md
    ├── implementation.md
    └── test-report.md
```

### 每个 team 的 prompt 模板

发起 team 时的标准 prompt：

```
为 {req-id}（{需求名称}）组建开发团队。

创建 3 个 teammate：

1. Architect：
   - 读取 .pi-dev/requirements/{req-id}.md 了解需求
   - 分析 /Users/z/pi-mono/{ts-path} 下的 TS 参考代码
   - 输出完整设计文档到 .pi-dev/designs/{req-id}-design.md
   - 设计文档必须包含：需求分析、业务流程、数据设计、接口设计、技术方案、非功能设计、测试策略

2. Developer（依赖 Architect 完成）：
   - 读取 .pi-dev/designs/{req-id}-design.md
   - 在 modules/{module}/ 下实现 Java 代码
   - 每个类写完后运行 ./gradlew compileJava 验证
   - 遇到问题给 Architect 发消息讨论

3. Tester（依赖 Developer 完成）：
   - 编写 JUnit 5 测试 + WireMock 集成测试
   - 运行 ./gradlew test 验证
   - 运行 ./gradlew check 验证代码质量
   - 测试失败给 Developer 发消息附带错误信息

文件所有权：
- Architect: .pi-dev/designs/*
- Developer: modules/*/src/main/java/**
- Tester: modules/*/src/test/java/**

完成后所有人报告给我，我来 review 和 commit。
```

---

## 八、实施步骤

| 步骤 | 操作 | 产出 |
|---|---|---|
| 1 | 创建 `/Users/z/pi-mono-java/`，git init | git 仓库 |
| 2 | 初始化 Gradle multi-module 骨架（6 个 module） | 可编译空项目 |
| 3 | 配置 `.claude/settings.json` 启用 Agent Teams | Teams 可用 |
| 4 | 创建 `CLAUDE.md` 定义角色约定 | 项目指令 |
| 5 | 创建 `.pi-dev/` 目录、模板文件 | 状态管理 |
| 6 | 将上方 61 个需求写入 `.pi-dev/requirements/` | 需求清单 |
| 7 | 生成 `docs/architecture.md` | TS 架构参考 |
| 8 | 为 AI-001 创建 Agent Team 验证全链路 | 验证方案可行 |
| 9 | 按优先级批量执行需求 | Java 代码 |

### 验证

- `./gradlew build` — 编译
- `./gradlew test` — 测试
- `./gradlew check` — 质量
- `.pi-dev/project-state.md` — 进度
