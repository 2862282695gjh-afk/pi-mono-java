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

### 2.1 命令目录:启动期可扩展,运行期静态(WebCommandCatalog)

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

**缓存策略(复审③)**:Extension 仅启动期经构造注入,运行期静态——`GET /commands` 幂等可安全缓存(内存缓存即可,无需 ETag/失效机制);运行期动态注册为后续演进,首版不做。

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

**[复审①已采纳——批注部分属实,架构补全如下]**

查证:compaction 事件到 SSE 的桥接**已存在**(`RuntimeExecutionCoordinator:91` `holder.subscribeCompaction(projector::onCompactionEvent)`,projector 含 Started/Completed/Failed 三分支投影)——但批注核心成立:**该桥接只在 events 请求的执行期存活**(subscribe 随 submit 开始、finish 时 unsubscribe),且 idle 会话 Holder 已释放,命令端点无法仅凭 sessionId 触达 `ManagedAgentSession.compact()`。补全架构:

**1. 会话重建(可压缩 Session 的取得)**:`POST /compact` 经 `AgentSessionFactory` 按持久化配置重建 `ManagedAgentSession`(与 Cron 触发同款路径——Cron 无入站请求也能压缩,证明该路径可行),压缩完成后释放重建的 Session(不留 Holder)。

**2. 事件投影到"命令请求对应 SSE"——不承诺**:批注正确指出 commands POST 返回后没有活跃订阅者。修订承诺为两层:
- **持久层(可靠)**:compaction 事件写入既有持久化事件流(RuntimeEntryCodec 已编码 compaction 事件)——断线重连/新开页面经 `GET /events` 历史分页可见"已压缩"结果,这覆盖状态查询需求
- **实时层(尽力)**:若压缩期间用户恰在 streaming(409 互斥已排除此态)——实际不成立;因此**首版 /compact 无实时进度,只有启动确认 + 持久化结果**。时间线显示"压缩已启动,结果稍后出现在历史中"

**3. abort 取消语义**:手动压缩经重建 Session 独立执行,与会话的 abort(CancellationToken)不共享;压缩任务自身可经 `CompletableFuture.cancel()` 取消——**首版不暴露取消端点**(压缩通常秒级完成,取消价值低),登记 DEFERRED 待真实需求。

**结论**:`POST /compact` 承诺修正为"同步返回启动确认;结果经持久化事件流可查;无实时 SSE 进度"——不再声称"经既有 events SSE 反馈进度"。

**[复查⑤已采纳——批注属实,重建流程补全]**。查证:`AgentSessionFactory.create()`(`AgentSessionFactory.java:69`)确实只组装新 Agent/工具,不读历史——若直接 compact 将压缩空上下文。修订重建流程为三步:

1. **恢复历史**:经 `RuntimeSessionEngineRegistry` 同款路径(`RuntimeSessionEngineRegistry.java:120` 有 `agent().replaceMessages(messages)` 先例)——从 RuntimeEntryRepository 读有效上下文条目,`RuntimeEntryCodec` 解码为 messages,`replaceMessages` 注入重建的 Session
2. **执行压缩**:`ManagedAgentSession.compact()`(内部触发 SessionCompactor + 事件)
3. **写回与释放**:压缩结果条目经 RuntimeEntryCodec 写入持久化流,try-with-resources 释放重建 Session

**并发围栏**:压缩期间(读历史→写 compaction entry)对该 sessionId 的普通 `POST /events` 提交互斥——实现为 sessionId 级读写锁(读历史/写结果为写侧,普通提交为读侧排队;或复用既有 per-session 操作锁 `RuntimeSessionEngineRegistry.operationLock`);避免压缩 summary 覆盖并发新消息。**端到端测试**:idle 有历史会话压缩后,下一次 prompt 携带 summary + 保留消息(非空上下文)。



### 2.4 `/skill:<skill-name> [arguments]`(发现/补全/执行)

命名规则:以 `skill:` 为前缀的命名空间,`^skill:[a-z0-9-]+$`(与命令名 `^[a-z][a-z0-9-]*$` 区分,不冲突)。

- **发现/补全**:`GET /commands` 的 descriptor 对每个已安装 Skill 生成 `skill:<name>` 条目(category=`skill`,argsHint 来自 Skill 元数据)——菜单输入 `/skill:` 前缀时过滤展示
- **执行(复查⑥修订)**:`POST /sessions/{id}/commands/skill:<name>`(SERVER,`produces = TEXT_EVENT_STREAM_VALUE`)——服务端替用户提交消息并**在同一响应中返回 SSE**:内部等价 `POST /events {message: skillArgs}`(submit + SseEmitter attach,`RuntimeEventController.submit` 同款模式),**前端只发一次请求**、输出经该流实时返回;断线重连走既有 `GET /events` 历史分页。descriptor 以 `streaming: true` 标注,前端用 fetch-stream 处理;**禁止**"服务端 submit + 前端另行 POST events"的双请求形态(那是二次 prompt,会重复执行 skill)
- 与 events 守卫的关系:`skill:*` 注册进 Catalog → 守卫拦截 `  /skill:foo` 一致
- 权限:Skill 的权限/信任边界沿用现有 Skill 加载体系,命令层不另设

**[复审②已采纳——契约贯通修订]**:
- **名称模式统一**:`^([a-z][a-z0-9-]*|skill:[a-z0-9-]+)$`——主文档 Controller `@PathVariable` 校验、Catalog parser、TS `SlashCommandDescriptor.name` 注释同步;冲突测试:内置命令名不得以 `skill:` 开头(注册期检查)
- **category 枚举扩容**:`'session' | 'conversation' | 'system' | 'skill' | 'extension'`(tui 随 TUI 删除移除);DTO 与 TS 同步
- **执行流定稿(复查⑥修正)——命令端点直接返回 SSE**:`POST /commands/skill:<name>` 内部等价 submit(message=skillArgs) 并在同一响应 attach SseEmitter——前端单请求拿全流,不另行 POST events(那会二次提交);断线重连走 `GET /events` 历史分页;不新增第二套运行事件通道。



### 2.5 Extension 自定义命令注册

```java
public interface SlashCommandExtension {
    List<WebCommandDefinition> commands();   // 启动期经 WebCommandCatalog 构造注入
}
```

- 命名规则:与内置/`skill:` 前缀冲突 → **注册期抛错**(启动失败,显式暴露冲突);建议约定 Extension 命令以自有前缀命名(如 `git:`、`jira:`)
- descriptor 完整暴露(name/description/argsHint/category/executionMode),`?all=true` 可见性同内置
- 无会话策略:Extension 自声明(definition 增加 `requiresSession` 布尔,SERVER 默认 true,CLIENT_LOCAL 恒 false)

**[复查⑦已采纳——统一 namespace grammar]**。名称规范定义为:

```
commandName    := builtinName | namespacedName
builtinName    := ^[a-z][a-z0-9-]{0,31}$                       (内置,无冒号)
namespacedName := ^[ns]:[a-z0-9][a-z0-9-]{0,31}$              (带命名空间)
ns             := skill | <extensionId>                        (skill 为保留 ns)
extensionId    := ^[a-z][a-z0-9-]{0,15}$                      (注册时声明,不可为 skill/内置保留字)
```

- **单一正则进所有层**:Controller `@PathVariable`、Catalog parser、TS 类型注释、URL 编码(冒号合法路径字符,无需转义)统一 `^(?:[a-z][a-z0-9-]{0,31}|[a-z][a-z0-9-]{0,15}:[a-z0-9][a-z0-9-]{0,31})$`
- **保留 namespace**:`skill` 为系统保留;Extension 的 `extensionId` 注册时声明且不得与内置命令名/`skill` 冲突(注册期抛错)
- **冲突测试**:内置名带冒号拒绝、`skill:foo` 仅系统注册、`git:status` 走 Extension ns、非法 ns(大写/超长)拒绝
- 原 2.5 的"建议自有前缀"从建议升级为 grammar 强制


**[复审③已采纳]** 2.1 标题与"动态性影响"段改为"**启动期可扩展,运行期静态**":Extension 仅经 Spring 构造注入(启动期组合),运行期无注册/卸载 API;`GET /commands` 保持幂等可安全缓存,无需缓存失效机制。真正的运行期动态(安装/卸载/重载 + 目录版本)登记为后续演进,首版不做。


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

**[复审④已采纳——主文档标记 Superseded]**:frontend-slash-commands.md 头部状态改为 Superseded(指向本文),其"TUI 27 命令/静态 5 命令/同步无 SSE/compact 移出/旧 category"等历史结论不再具执行力;本文成为单一规范(完整覆盖 HTTP 契约/DTO/前端接线/测试)。开发以本文为准。


## 测试与验证(增量)

- 后端:`GET /sessions` 列表端点分页/过滤;`POST /compact` 异步启动 + 事件投影;skill 注入走 events;Extension 注册冲突;executionMode 契约(⑧)
- 前端:resume 选择列表交互/前缀匹配;compact 进度系统消息;`/skill:` 补全;SystemNoticeStack 渲染(⑨);无会话策略表全覆盖(⑩)
