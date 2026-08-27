# 前端 Slash Commands:现状 Gap 分析与新范围设计

> 模块:`coding-agent-cli` + `frontend/`
> 状态:Proposed(基于 frontend-slash-commands.md 前八轮评审后的范围扩展)
> 日期:2026-08-27
> 源码基线:upstream/main `1813c601`(本地 `ae4fa244` 合并后)

---

## 第一部分:当前实现现状与 Gap(前后端逐项对照)

### 1.1 后端已实现(TUI 时代遗留 + 新增)

| 能力 | 位置 | 状态 | 说明 |
|---|---|---|---|
| `SlashCommand` SPI | `command/SlashCommand.java` | ✅ 已实现 | name/description/execute(context, arguments) |
| `SlashCommandRegistry` | `command/SlashCommandRegistry.java` | ✅ 已实现 | **非 Spring Bean**(类注释明确"任何 Runtime Host 都不会自动启用命令解析");含私有 `parse()`:trim + 首空格分隔 |
| `SlashCommandSession` 端口 | `command/SlashCommandSession.java` | ✅ 已实现 | 宿主无关 Session 操作(model/thinking/compact/name);**注释明确"首版 Runtime 不提供该端口实现,也不会注册 Slash Command"** |
| `SlashCommandOutput` 端口 | `command/SlashCommandOutput.java` | ✅ 已实现 | `println(String)` 函数式接口 |
| 内置命令 | `command/builtin/` | ✅ 4 个 | ModelCommand / ThinkingCommand / CompactCommand / NameCommand(对 SlashCommandSession 编程) |
| TUI 交互模式 | `mode/` | ❌ **已移除** | 上游近期重构删除了 TUI/InteractiveMode——"TUI 有 27 个命令"已成历史,现存命令仅 4 个 |
| Runtime HTTP 命令端点 | `runtimeapi/web/` | ❌ **未实现** | 现有 Controller 仅:sessions CRUD/events/configuration/control;无 `/commands` |
| `/events` slash 守卫 | `runtimeapi/event/` | ❌ 未实现 | `RuntimeEventService` 直接 `agent.prompt(message)`,无任何 `/` 拦截 |
| 真实手动压缩 | `session/compaction/SessionCompactor.java` | ✅ 已实现 | `compact()` 返回 `CompletableFuture<SessionCompactionResult>`,含 Started/Completed/Failed 事件——**批注④当年"Runtime 无手动压缩"的前提已过时,服务端能力现已存在**(未暴露 HTTP) |
| 会话列表 API | `RuntimeSessionController` | ❌ 未实现 | 仅有 `GET /sessions/{sessionId}` 单查;**无列表端点**——前端 `threads` 只是本地内存数组(创建时 push,刷新即失),`/resume` 需要的"检索已有会话"无后端支撑 |
| Extension 命令注册 | `extension/` | ❌ 未实现 | ExtensionPoint 仅有 tools 注册;无 commands 注册通道 |

### 1.2 前端已实现(Vue 工作台)

| 能力 | 位置 | 状态 | 说明 |
|---|---|---|---|
| Composer 输入 | `ComposerBox.vue` | ✅ 已实现 | Enter 发送/Shift 换行/running 态 steer/queue 切换 |
| `/` 检测与命令菜单 | — | ❌ 未实现 | 无任何 slash 逻辑(前设计文档的 CommandMenu/useSlashCommands 均未落地) |
| 会话恢复 | `App.vue:73 resumeSession()` | ⚠️ 半实现 | `DevDiagnostics`(仅开发构建)可按 sessionId 恢复 + loadHistory;**无会话列表 UI**,生产用户无法发现历史会话 |
| 会话列表数据 | `App.vue:25 threads` | ⚠️ 本地内存 | `ThreadSummary[]`,仅当前页生命周期;无持久化/无服务端拉取 |
| 模型/思考切换 | `useRuntimeApi.changeModel/changeThinking` | ✅ 已实现 | PUT + If-Match 完整(前设计别名的落点) |
| 清视图 | `clearSessionView()` | ✅ 已实现 | `useRuntimeApi.ts:205`,deleteSession 复用 |

### 1.3 关键 Gap 结论

1. **后端命令体系"有骨无肉连"**:SPI/Registry/4 命令/Session 端口齐备但**没有任何宿主接线**(Runtime Host 明确不启用),TUI 宿主已删——命令层成了孤儿代码,等一个新宿主(HTTP)激活。
2. **前设计文档的多数前提已变**:"TUI 27 命令"(实为 4)、"Runtime 无手动压缩"(SessionCompactor 已存在)、`SlashCommandContext(AgentSession+OutputWriter)`(已重构为 SlashCommandSession 端口)。
3. **`/resume` 的真正缺口是会话列表端点**,不是命令本身;`/compact` 的缺口是 HTTP 暴露(能力已有);`/skill:` 与 Extension 注册是全新功能。
4. **新需求的"静态 Catalog"约束已被批注推翻**——Extension 注册要求目录动态化。

---

## 第二部分:新范围设计(四项新需求 + 原有五命令)

### 2.1 命令目录动态化(WebCommandCatalog 重设计)

原"静态注册表"改为**运行时可注册的目录 Bean**,三来源:

| 来源 | 注册时机 | 命令 |
|---|---|---|
| 内置 | Bean 构造 | model/thinking/new/help/settings(原五命令,决议不变) |
| 内置(新需求) | Bean 构造 | resume/compact/skill(见 2.2—2.4) |
| Extension | `ExtensionPoint.commands()` 扩展点 | 自定义命令(见 2.5) |

```java
@Component
public class WebCommandCatalog {
    private final Map<String, WebCommandDefinition> commands = new ConcurrentHashMap<>();

    public WebCommandCatalog(List<SlashCommandExtension> extensions) {
        // 内置注册(终审①的 compact constructor 约束不变)
        // Extension 注册:重复名抛错(注册期冲突,命名规则见 2.5)
    }

    public void register(WebCommandDefinition def) { /* 冲突即抛 */ }
    public ParsedSlashInput parseSlashInput(String text) { /* 1.3 共享规范 + hasSeparator */ }
    public boolean isRegistered(String name) { ... }
    public List<CommandDescriptorDTO> descriptors(boolean all) { ... }
}
```

**动态性影响**:`GET /commands` 不再是"幂等静态"——Extension 可在启动后注册(仅启动期,运行期注册暂不开放,首版目录启动后冻结);响应仍可缓存至 Extension 重载。

### 2.2 `/resume`(检索并恢复已有会话)

**前置依赖(新端点)**:`GET /campusclaw-service/v1/sessions?agentId=&limit=&cursor=`
返回会话摘要列表(sessionId/标题/最后活动时间/状态)。这是 Gap 1.3-3 指出的真缺口,**独立于命令先行落地**(前端侧栏也需要它)。

命令行为(交互设计):
- `/resume` 空参:前端拉取会话列表 → 渲染**选择列表**(复用 CommandMenu 浮层形态,数据换为会话项)→ 用户选择后走既有 `App.resumeSession(sessionId)`
- `/resume <sessionId前缀>`:前缀唯一匹配直接恢复;多匹配展示候选;无匹配提示
- **纯前端编排命令**(CLIENT_LOCAL 变体 + 读新列表端点);无会话时同样可用(恢复正是"从无会话到有会话")——豁免 session 守卫
- effects:`sessionRestored`(前端:切换 thread/加载历史/滚到底)

### 2.3 `/compact`(真实手动压缩 + 进度反馈)

**前提变化**:批注④的"Runtime 无手动压缩"已过时——`SessionCompactor.compact()` 已存在(异步 + Started/Completed/Failed 事件)。缺口是 HTTP 暴露与进度通道。

设计(打破原"同步 <100ms 无 SSE"约束——压缩是长操作):
- `/compact` → `POST /sessions/{id}/compact`(新端点,SERVER 命令)→ 触发 `SessionCompactor.compact(customInstructions)`
- **进度经既有 events SSE 流**:`SessionCompactionStartedEvent/CompletedEvent/FailedEvent` 已在事件体系内,前端 projector 增加 compacting/compacted 投影(时间线显示系统消息"正在压缩…/已压缩 N 条消息")
- 命令本身返回 `{kind:'ok', output:'压缩已启动', effects:{}}`(异步启动确认),结果由事件流送达
- 运行态互斥:streaming 中执行 → 409(复用既有互斥语义)

### 2.4 `/skill:<skill-name> [arguments]`(发现/补全/执行)

命名规则:以 `skill:` 为前缀的命名空间,`^skill:[a-z0-9-]+$`(与命令名 `^[a-z][a-z0-9-]*$` 区分,不冲突)。

- **发现/补全**:`GET /commands` 的 descriptor 对每个已安装 Skill 生成 `skill:<name>` 条目(category=`skill`,argsHint 来自 Skill 元数据)——菜单输入 `/skill:` 前缀时过滤展示
- **执行**:`POST /sessions/{id}/commands/skill:<name>`(SERVER)→ 将 `<arguments>` 作为用户消息**注入当前会话上下文**(等价用户手打,走正常 agent 循环);返回 `{kind:'ok', output:'已提交技能 xxx'}`,实际执行结果经 events SSE
- 与 events 守卫的关系:`skill:*` 注册进 Catalog → 守卫拦截 `  /skill:foo` 一致
- 权限:Skill 的权限/信任边界沿用现有 Skill 加载体系,命令层不另设

### 2.5 Extension 自定义命令注册

```java
public interface SlashCommandExtension {
    List<WebCommandDefinition> commands();   // 启动期经 WebCommandCatalog 构造注入
}
```

- 命名规则:与内置/`skill:` 前缀冲突 → **注册期抛错**(启动失败,显式暴露冲突);建议约定 Extension 命令以自有前缀命名(如 `git:`、`jira:`)
- descriptor 完整暴露(name/description/argsHint/category/executionMode),`?all=true` 可见性同内置
- 无会话策略:Extension 自声明(definition 增加 `requiresSession` 布尔,SERVER 默认 true,CLIENT_LOCAL 恒 false)

### 2.6 无会话策略统一表(替代边界表的旧表述)

| 命令类 | 无会话时 |
|---|---|
| CLIENT_LOCAL(new) | ✅ 可执行(豁免守卫,终审复查⑥) |
| `/resume` | ✅ 可执行(恢复语义本就跨会话) |
| `/help` | ✅ 可执行(无会话依赖) |
| SERVER 且 requiresSession(model/thinking 查询/settings/compact/skill:*/Extension 默认) | ❌ 提示先创建会话 |

### 2.7 executionMode 进契约(终审复查⑧,前文档遗漏)

`CommandDescriptorDTO` 与 TS `SlashCommandDescriptor` **均补 `executionMode: 'SERVER' | 'CLIENT_LOCAL'`** 字段;GET 响应示例、matcher 契约测试同步——前端分流(`isClientLocal` 判断)不再依赖不存在的字段。

### 2.8 `/new` 系统消息渲染接线(终审复查⑨)

`clearSessionView()` 后进入引导态,`ConversationTimeline` 不渲染。接线方案:`App.vue` 模板在 `!hasSession` 分支的 `welcome-scroll` 顶部插入独立 `<SystemNoticeStack :messages="systemMessages" />` 组件(有/无会话两分支共用同一数据源);或 clear 前以 toast 呈现。**选用组件方案**(与 3.6 系统消息体系统一)。测试:有会话与无会话 `/new` 均有可见反馈。

---

## 第三部分:对前文档(frontend-slash-commands.md)的修订清单

| 原结论 | 修订 |
|---|---|
| "TUI 27 命令" | 改"4 个内置命令,TUI 宿主已移除" |
| 首版 5 命令静态目录 | 目录动态化:内置 5+3(resume/compact/skill 通道)+ Extension;启动期注册、运行期冻结 |
| /compact 移出 | 恢复:SessionCompactor 已存在,新 POST /compact 端点 + SSE 进度 |
| 同步 <100ms 无 SSE | 命令确认仍同步;compact/skill 的**执行结果**经 events SSE |
| webCapable 三态(基于 TUI-only 命令) | 保留概念但语义更新:目录含 skill:*/Extension 动态项,`hotkeys` 等 TUI-only 已随 TUI 删除自然消失 |
| SlashCommandContext(AgentSession+OutputWriter) | 现实为 SlashCommandSession 端口 + SlashCommandOutput;Web 侧仍用 CommandExecutionContext(前设计),但 model/thinking/compact 可直接委托给既有 4 个内置命令的实现(它们已对端口编程) |

## 测试与验证(增量)

- 后端:`GET /sessions` 列表端点分页/过滤;`POST /compact` 异步启动 + 事件投影;skill 注入走 events;Extension 注册冲突;executionMode 契约(⑧)
- 前端:resume 选择列表交互/前缀匹配;compact 进度系统消息;`/skill:` 补全;SystemNoticeStack 渲染(⑨);无会话策略表全覆盖(⑩)
