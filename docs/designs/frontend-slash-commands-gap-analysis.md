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

**[横向㉓已采纳——服务端 idPrefix 查询]**:`GET /sessions?sessionIdPrefix=<prefix>&limit=20` 受授权范围约束(按调用者可见 agent 域过滤,agentId 参数仅为过滤条件**非**授权凭据——鉴权沿用 Runtime API 既有机制),返回 `matches[] + hasMore`(上限 20):恰 1 条直接恢复;多条展示候选;hasMore=true 或超上限提示"前缀过短"。测试:跨页同前缀、越权 agentId 不可见、删除/新建导致列表变动时重查。


### 2.3 `/compact`(真实手动压缩 + 进度反馈)

**前提变化**:批注④的"Runtime 无手动压缩"已过时——`SessionCompactor.compact()` 已存在(异步 + Started/Completed/Failed 事件)。缺口是 HTTP 暴露与进度通道。

设计(经复审①/继续复查①④修订后的**当前契约**,前文旧 SSE 表述作废):
- `/compact` → `POST /sessions/{id}/commands/compact`(SERVER,requiresSession=true)→ 重建会话(含历史恢复)后触发 `SessionCompactor.compact(customInstructions)`
- **无实时 SSE 进度**:命令同步返回 `{kind:'ok', output:'压缩已启动', operationId, effects:{}}`(启动确认,⑩);**结果经持久化事件流可查**(compaction 事件已入 RuntimeEntryCodec 持久化,断线重连/刷新后 `GET /events` 历史分页可见"已压缩"条目)
- 并发保护:completed 走 leaf 条件追加(继续复查④);failed/终态写入走 operation-open 校验(⑲);进行中单飞 409 `COMPACTION_IN_PROGRESS`;超时后进入禁止态 409 `COMPACTION_SUSPENDED`(⑳+㉑)
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

**[继续复查④已采纳——CAS 落地为仓储条件追加]**。批注属实:现有 `RuntimeSessionRepository` 无 entryVersion/compare-and-set(查证:append 走 `lockNextSequence` 分配序列,`MyBatisRuntimeSessionRepository:209`)。修订为实现级方案:

1. **新增仓储方法** `tryAppendCompactionIfLeafUnchanged(sessionId, expectedLeafId, entry)`:单事务内 `lockNextSequence` + **校验 session.activeLeafId == expectedLeafId**(不等则返回失败,不插入、不推进 sequence)——复用既有行锁语义实现条件追加,不引入新版本列
2. 压缩前记录 `expectedLeafId = session.getActiveLeafId()`;completed 经该方法原子写入——成功→提交;失败(leaf 已被并发消息推进)→ 同一回调内转投**无条件追加 failed entry**(errorCode=CANCELLED_BY_NEW_MESSAGES,见继续复查⑫),释放重建 Session——POST 此时早已返回 operationId,取消事实经持久化 failed entry 呈现,不改 HTTP 响应
3. **重复 /compact 幂等**:per-session 单飞标记进行中 → 409 `COMPACTION_IN_PROGRESS`;完成后重复 → 正常再压(新 leaf 快照)
4. **并发集成测试**:两线程竞争(普通 events append vs compact 写入)只允许一个成功提交 compaction entry,另一个可见新 leaf 后取消

**[继续复查④已采纳]** 见上方修订:entryVersion 改为基于既有 `lockNextSequence` 行锁的条件追加方法(校验 activeLeafId),失败路径释放单飞标记并清理重建 Session;并发集成测试纳入清单。

**[继续复查⑦已采纳——补 operation 状态模型]**。批注属实:启动确认已返回,CAS 失败无法再改 HTTP 响应,且不写 entry 则历史也无失败痕迹。修订:

- **持久化 compaction operation 记录**:POST 成功启动时即写入 `compaction_started` entry(含 `operationId`);future 完成回调写 `compaction_completed`/`compaction_failed` entry(失败含原因,如 `CANCELLED_BY_NEW_MESSAGES`)——**三类状态均入持久化事件流**,GET /events 可见
- **POST 响应**返回 `{kind:'ok', output:'压缩已启动', operationId, effects:{}}`——前端记录 operationId,历史刷新看到对应 completed/failed 时呈现"已压缩 N 条"/"因新消息取消,请重试"
- **单飞标记**以 operation 终态回调(completed/failed 均释放)为准,不以 HTTP 返回为准
- (可选后续)状态查询端点 `GET /sessions/{id}/compact/{operationId}` 直查单条——首版靠历史分页即可,登记后续

**[继续复查⑩已采纳——operationId 进正式契约]**:

**DTO/TS**:`CommandResultDTO` 与 TS `SlashCommandResult` 增加可选 `operationId: string`(仅异步命令返回,其余为 null);JSON 示例:

```json
{ "kind": "ok", "output": "压缩已启动", "operationId": "cpt_a1b2c3", "effects": {} }
```

**三类持久化 entry 契约**(复用 RuntimeEntryCodec 既有 compaction 通道,类型与 payload):

| entry type | payload 必含 | 备注 |
|---|---|---|
| `SESSION_COMPACTION_STARTED`(新增类型) | `operationId`, `startedAt` | 启动即写 |
| `SESSION_COMPACTION_COMPLETED`(既有) | `operationId`, `reason`, `keptMessages`, `removedMessages`, `completedAt` | 既有 compactionEntry 补 operationId 字段 |
| `SESSION_COMPACTION_FAILED`(新增类型) | `operationId`, `errorCode`(如 CANCELLED_BY_NEW_MESSAGES), `failedAt` | |

**GET /events 投影**:projector 对 started/failed 增加投影(既有 completed 已投影);前端 runtimeEventProjector 识别 payload.operationId 与本地记录的启动 operationId 关联(而非文案匹配)。**测试断言同一 operationId 的 started→终态关联**,不止看展示文本。


**[继续复查⑪已采纳——存储位置与分支行为定稿]**。查证:Codec 的 `toAgentMessage` 仅恢复 USER/ASSISTANT/TOOL_RESULT/**COMPACTION_COMPLETED**(转 summary message),其余类型恢复为 null(`RuntimeEntryCodec:272` default 分支)——意味着 started/failed 天然不进 Agent 上下文,completed 会进(以 summary 形式,这是**期望行为**:压缩结果本就应成为上下文)。

定稿:
- **started/failed 不入对话上下文**:新增的两个类型走 codec default(null)——无需额外排除规则;但它们**会推进 activeLeafId**(普通 append)——因此 **expectedLeafId 采样点定为 started entry 写入之后**(流程:写 started → 记 leaf → 读历史 → 压缩 → completed 条件追加 / failed 无条件追加;started 自身推进 leaf 不影响,因其后采样)
- **completed 入上下文**:既有 summary message 行为保留(压缩语义);其 leaf 推进即条件追加本身
- **不引入独立 operation 表**:三类 entry 全走主分支持久化(与既有 completed 同款),Codec 恢复规则如上;避免双存储一致性
- CAS 语义因此自洽:expectedLeafId 在 started 之后采样,普通消息只会推进 leaf 触发 CAS 失败——started 自身不会再干扰

**[继续复查⑫已采纳——终态写入用独立无条件追加]**。批注属实:leaf 已被并发消息推进,failed entry 若仍走条件追加(期望旧 leaf)将二次失败,operation 卡在 started。修订:

- **两个仓储方法分工**:`tryAppendCompactionIfLeafUnchanged(expectedLeafId, entry)` 仅用于 **completed**(成功路径,条件保证 summary 不覆盖新消息);**failed 走独立的无条件追加** `appendOperationTerminal(entry)`——以**当前 leaf** 安全追加(复用 appendLocked 行锁语义),不校验 expectedLeafId、不进 Agent 上下文(⑪:codec default 恢复 null),因此与新消息共存无害
- **completed 的 CAS 失败转投 failed**:条件追加返回失败 → 在同一回调内改调 `appendOperationTerminal(failed entry, errorCode=CANCELLED_BY_NEW_MESSAGES)`——保证 started 后必有且仅有一个终态
- **单飞标记释放**在 finally(completed 成功、failed 成功、二者均异常)统一执行——与终态写入同一路径,不泄漏
- **测试**:模拟 leaf 竞争(压缩期间插入普通消息)断言 started 后恰一个 completed 或 failed;两条终态并存为缺陷

**[继续复查⑭已采纳——终态写入失败的后台补偿]**。批注属实:finally 释放在终态 append 异常时会导致无历史记录 + 标记释放 + 新 compact 可启动——不变量破坏。修订:

- **单飞标记的释放条件收紧**:仅在**终态 entry 成功持久化后**释放;终态 append 异常时**标记保持占用**(阻止新 compact),转入恢复路径
- **后台 reconcile**:新增轻量恢复任务(复用既有 `SessionCleanupWorker` 的调度形态,或随其扫描):周期发现"started 存在且超时(如 5 分钟)无终态"的 operation → 补写 `compaction_failed`(errorCode=`TERMINAL_WRITE_RECOVERY`)并释放标记——保证不变量最终成立(至少一次终态,延迟可观察)
- **三类失败源全覆盖**:Factory 重建失败 / compactor future 异常 / terminal append 异常——统一走"写 failed(尽力)+ 失败则交 reconcile 兜底";append 失败不原地无限重试(避免占用请求线程)
- **测试**:注入 terminal append 异常 → 断言标记未释放、reconcile 补写 failed、最终历史呈现单一终态(非悬挂 started)

**[继续复查⑮已采纳——admission guard 持久化]**。批注属实:进程内标记 + future 重启即失,reconcile 未跑前新 /compact 会当空闲再启动,双 operation 破坏单操作不变量。修订:

- **admission 检查落库**:POST /compact 的准入在同一 session 行锁事务内查询持久化 entry——存在未终态的 started:未超时 → 409 `COMPACTION_IN_PROGRESS`;**已超时 → 同步补写 failed**(errorCode=`TERMINAL_WRITE_RECOVERY`)后**进入禁止态、不启动新压缩**(㉑修订:原'正常走新压缩'已被取代——宽限期跨实例不可证明)
- **进程启动扫描**:启动时(或 reconcile 首轮)全量扫描悬挂 started(有 started 无终态)补 failed——多实例部署下任一实例启动即清理
- **内存单飞标记降级为快速路径优化**(挡住同进程高频重复),正确性完全由持久化准入保证——两机制不冲突:准入是 source of truth
- **重启并发集成测试**:写 started → 模拟重启(丢内存态)→ 立即新 /compact → 断言 409(未超时)或补 failed 后成功(超时),全程无双 operation

**[继续复查⑯已采纳——准入与登记同一事务]**。批注属实:查询在锁内、写入在锁外,两个并发请求都能看到"无 started"各自启动。修订准入序列为**单个数据库事务**内完成(持有 session 行锁):

```
BEGIN(行锁该 session)
  1. 查未终态 started:
     存在且未超时 → ROLLBACK,返回 409 COMPACTION_IN_PROGRESS
     存在且已超时  → 补写 failed(TERMINAL_WRITE_RECOVERY)
  2. 生成 operationId,写入新 compaction_started entry
  3. (同进程)登记内存单飞快速路径标记
COMMIT
→ 事务提交后才启动压缩 future
```

- 准入、超时补写、started 写入、单飞登记**四步一事务**——并发两请求串行化于行锁,第二个必见第一个的 started → 409
- **集成测试**:两并发 POST /compact 断言恰一条 started 持久化 + 一个 409 响应

**[继续复查⑰已采纳——内存标记移出事务]**。批注属实:内存标记不可随数据库回滚——提交失败时标记遗留而库中无 started,进程内永久误报 409。修订序列:

```
DB 事务(行锁):
  1. 查未终态 started(未超时→回滚 409;超时→补 failed)
  2. 生成 operationId,写入 started
COMMIT
afterCommit:
  3. 登记内存快速路径标记(幂等 putIfAbsent)
  4. 启动压缩 future
  5. 若 3/4 失败:同 operationId 尽力写 failed → 仍失败交 reconcile
```

- 事务内**只剩**准入/补写/started 三步;内存标记与 future 启动在 **afterCommit**——提交失败则三者皆无,一致
- 提交成功但启动失败:started 已在库,走第 5 步写 failed(该场景由⑭的 reconcile 兜底)
- 内存标记清理幂等(释放时 compare-and-set 该 operationId,防误清新 operation)
- **测试**(⑱修正):①强制 started 写入或 COMMIT 失败 → afterCommit 未执行、无内存标记、下次请求不误 409;②COMMIT 成功后第 3/4 步失败 → started 已持久化且终态可达(failed),非悬挂

**[继续复查⑱已采纳]**。批注属实:afterCommit 仅在成功提交后调用,"回滚发生在内存登记之后"时序不可能构造。测试改为验证**提交边界**:

- **测试 A(提交失败路径)**:强制第 2 步写 started 或 COMMIT 失败 → 断言 afterCommit 回调**完全未执行**、内存标记不存在、下一次 /compact 不被误 409(正常走准入)
- **测试 B(提交成功、afterCommit 失败路径)**:COMMIT 成功后强制第 3/4 步抛出 → 断言 started 已持久化 + 同 operationId 终态可达(第 5 步 failed 或 reconcile 补写),呈现 failed 而非悬挂

**[继续复查⑲已采纳——终态写入改为 operationId 条件状态转换]**。批注属实:超时补 failed 后旧 future 仍在跑,晚到的 completed/failed 回调会再写 entry,同一 operationId 出现两条终态。修订:

- **终态写入统一为状态转换**(completed 与 failed 同规则):同一 session 行锁事务内,`确认该 operationId 的 started 尚无终态 entry` → 追加终态;**已存在终态(含超时回收写的 failed)→ no-op 并释放本地资源**(不写 entry)——仓储方法收敛为 `tryAppendTerminalIfOpen(sessionId, operationId, entry)`,completed 的 leaf 条件校验作为其内部第二道检查(leaf 不符仍走取消转 failed 的既有路径,但仅在 operation 仍 open 时)
- ⑫的"无条件追加 failed"相应修正:无条件指不校验 leaf,**仍须校验 operation open**——两条围栏(leaf 防覆盖、operation 防重复终态)分层
- 内存标记释放保持幂等(⑰),晚到回调 no-op 后同样释放
- **跨实例/重启集成测试**:运行超过超时阈值 → 准入回收补 failed + 启动新 operation → 旧 future 随后完成 → 断言旧 operationId 恰一条 failed(回收写的那条)、新 operation 独立完整跑完

**[继续复查⑳已采纳——首版选保守分支:超时不夺执行权]**。批注属实:固定超时只拦终态写入不拦执行,慢任务会被误回收 + 双压缩重复耗资源;完整 lease(可续租 + epoch 夺取 + 双点校验)是正确终态但对首版过重。按批注给的降级选项定稿:

**首版契约(保守)**:
- 超时的 started → 准入**仅补写 failed(标记 `TIMEOUT_OBSERVED`),不立即启动新压缩**——新请求返回 `{kind:'error', output:'检测到超时的压缩任务,已标记失败,请稍后重试'}`;后台 reconcile 确认无活跃执行(进程内 future 已结束/或经宽限期)后才允许新压缩——"单操作"约束以**不并发**而非"夺取"来保证
- 内存快速路径标记仍由⑰的生命周期管理(晚到回调 no-op + 幂等释放,⑲)
- 完整 lease 方案(leaseId/epoch/expiresAt、worker 条件续租、原子夺取、重活前+写终态前双点 owner 校验)登记为后续演进——多实例/慢任务场景上量后再做

**测试**:①慢压缩(模拟超过阈值)不被误启动第二次(新请求得到 error 而非双跑);②标记 failed 后旧 future 晚到完成 → no-op(⑲覆盖);③reconcile 宽限期后新压缩可达

**[继续复查㉑已采纳——超时后进入禁止态,不自动放行]**。批注属实:跨实例/重启后宽限期不能证明旧 worker 停止,自动放行仍会双跑;首版无 lease/fencing 就不能把"宽限期已过"当放行条件。修订⑳的保守分支再收紧:

- 超时补 failed(`TIMEOUT_OBSERVED`)后该 session 进入 **compaction 禁止态**:新 /compact 一律 409 `COMPACTION_SUSPENDED`(提示"存在超时未确认的压缩任务,请联系运维或等待系统确认")——直至旧 worker **可观察地写入终态**(任何进程补写 completed/failed 均解除)或运维显式确认;不做基于时间的自动恢复
- 单实例部署下 reconcile 可观测本进程 future 终止(⑰生命周期),future 结束即写终态解除禁止态——常见路径自动化不受影响;跨实例悬挂需人工介入(登记运维 runbook)
- lease/fencing 若产品要求全自动恢复则为前置能力——按批注提示从"后续演进"升级为**条件性前置**(多实例部署启用 compact 前必须先实现);测试:旧 worker 于另一实例运行 + 当前实例重启超宽限期 → 不得启动第二个任务


**[横向㉔已采纳]** 2.3 正文四处同步最终语义:启动响应补 operationId(⑩);failed 改"operation-open 条件追加"(⑲,不校验 leaf 但校验 operation);超时后"进入禁止态不启动新压缩"(⑳+㉑);下游历史批注段落标注"已被后续修订取代"。









**[继续复查⑬已采纳]** ④决议第 2 条的"返回 kind:error"(与 POST 已返回 operationId 矛盾)与 ⑪ 流程的"条件追加 completed/failed"(failed 实为无条件)均已改写——全文只保留当前语义:completed 条件追加、CAS 失败转无条件 failed、终态由历史投影呈现。









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

**[继续复查②已采纳——服务端 skill 解析展开]**。批注属实:prompt 仅列技能目录,模型靠 Read 猜加载——命令必须服务端确定性解析。`POST /commands/skill:<name>` 服务端流程:

1. **解析 Skill**:按当前 agent 的 runtime 目录(SkillLoader 体系)查 `<name>`;**未知** → 稳定错误 `SKILL_NOT_FOUND`(400);**不可见**(不在当前 agent 绑定集)→ `SKILL_NOT_VISIBLE`(400);**disableModelInvocation=true** → `SKILL_INVOCATION_DISABLED`(400);**路径逃逸**(name 含 `..`/绝对路径等,grammar 已限 `[a-z0-9-]` 但仍防御性校验 filePath 在 workspace 边界内)→ `SKILL_PATH_INVALID`(400)
2. **组装消息**:读取 SKILL.md 正文,组装为一次 user message——`[Skill: <name>]
<SKILL.md 正文>

<用户 arguments>`(SkillPromptFormatter 同源模板);再经既有 submit+ SSE 路径发出
3. 展开大小上限:SKILL.md 正文截断(如 16KB,超出截断并在消息内注明)防超长注入
4. 错误码进 RuntimeErrorCode + i18n + 前端 friendlyError 同步清单


**[继续复查③已采纳——响应类型进契约]**。避免同一路径运行时猜 JSON/SSE:`CommandDescriptorDTO` 与 TS `SlashCommandDescriptor` **正式增加 `streaming: boolean`** 字段(默认 false):

- `streaming:false`(model/thinking 查询/new 除外为 CLIENT_LOCAL/help/settings):`POST /commands/{name}` → `application/json` `CommandResultDTO`(现状不变)
- `streaming:true`(skill:*、声明流式的 Extension):`POST /commands/{name}` → `text/event-stream`(submit+attach 模式)
- **前端按 descriptor 分流**:`execute()` 检查 `command.streaming`——false 走 `requestResult<SlashCommandResult>`(json),true 走既有 `sendMessage` 同款 SSE 消费(`consumeSse` 复用);无运行时嗅探
- 错误格式统一:流式命令在流开始前的校验失败(如 SKILL_NOT_FOUND)返回**普通 JSON 错误信封 + 400**——即 `produces` 声明两种,Spring 按异常路径协商;前端流式分支先检查 content-type 非 event-stream 则按 JSON 错误处理
- /help 输出列表标注各命令 streaming 标记

**[继续复查⑤已采纳]**:
- `CommandDescriptorDTO` / TS `SlashCommandDescriptor` **正式增加 `requiresSession: boolean`**(SERVER 默认 true,/help、/resume 等为 false;CLIENT_LOCAL 恒 false)
- **新增无会话端点** `POST /campusclaw-service/v1/commands/{name}`(无 sessionId 路径段):仅承接 requiresSession=false 的 SERVER 命令(help 等;resume 是 CLIENT_LOCAL 不经此端点,见继续复查⑧);requiresSession=true 的命令走该端点 → 400 `SESSION_REQUIRED`;带会话路径 `POST /sessions/{id}/commands/{name}` 行为不变(两端点共用同一 Invoker,权限一致)
- **前端判定同步**:`matchCommand`/submit 分流的豁免条件从"仅 CLIENT_LOCAL"扩为"`executionMode === 'CLIENT_LOCAL' || !command.requiresSession`";无会话时 executable 且 !requiresSession → 调无会话端点;2.6 表的 /help 规则由此可达

**[继续复查⑧已采纳——两类命令分开列]**:
- **`/resume`:纯 CLIENT_LOCAL,不调用任何 POST commands 端点**——网络请求仅 `GET /sessions`(会话列表);选择后前端本地 `App.resumeSession()`。requiresSession 对它无意义(恒 false)
- **`/help`:SERVER 且 requiresSession=false——走无会话端点 `POST /commands/{name}`**(该端点仅承接此类;承接对象修正为"help 等",resume 不在此列)
- execute 分流与测试明确各自网络请求:resume 断言仅 1 次 GET sessions、零 POST;help 断言 1 次 POST /commands/help






### 2.5 Extension 自定义命令注册

```java
public interface SlashCommandExtension {
    List<WebCommandDefinition> commands();   // 启动期经 WebCommandCatalog 构造注入
}
```

- 命名规则:与内置/`skill:` 前缀冲突 → **注册期抛错**(启动失败,显式暴露冲突);建议约定 Extension 命令以自有前缀命名(如 `git:`、`jira:`)
- descriptor 完整暴露(name/description/argsHint/category/executionMode),`?all=true` 可见性同内置
- 无会话策略:`requiresSession` 布尔(默认 true);**首版 Extension 限 SERVER**(横向㉒:注册 CLIENT_LOCAL 启动期拒绝)

**[横向㉒已采纳]** 首版 Extension 命令**限定 SERVER**(可选 streaming/requiresSession);`WebCommandCatalog` 注册时校验 Extension 声明 CLIENT_LOCAL → 启动期抛错(无浏览器扩展包/可信加载/handler 注册协议,前端识别模式却无代码可执行)。CLIENT_LOCAL 仅保留前端内置命令(new/resume);客户端扩展需签名/发布/版本协商/沙箱设计,另行立项。契约测试:Extension 注册 CLIENT_LOCAL 启动期拒绝。


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
| /compact 移出 | 恢复:SessionCompactor 已存在;命令端点 + 启动确认,结果持久化可查(**无实时 SSE**,见 2.3 当前契约) |
| 同步 <100ms 无 SSE | 命令确认仍同步;skill 是流式命令(SSE 同响应);compact **无实时 SSE**,结果持久化可查 |
| webCapable 三态(基于 TUI-only 命令) | 保留概念但语义更新:目录含 skill:*/Extension 动态项,`hotkeys` 等 TUI-only 已随 TUI 删除自然消失 |
| SlashCommandContext(AgentSession+OutputWriter) | 现实为 SlashCommandSession 端口 + SlashCommandOutput;Web 侧仍用 CommandExecutionContext(前设计),但 model/thinking/compact 可直接委托给既有 4 个内置命令的实现(它们已对端口编程) |

**[继续复查⑥已采纳]** 2.3 前文的 SSE 进度旧表述、修订清单两行、测试清单均已改写为当前契约(compact 无实时 SSE / skill 同响应 SSE / entryVersion 改 leaf 条件追加)——每命令单一契约,不依赖后文段落覆盖。


**[复审④已采纳——主文档标记 Superseded]**:frontend-slash-commands.md 头部状态改为 Superseded(指向本文),其"TUI 27 命令/静态 5 命令/同步无 SSE/compact 移出/旧 category"等历史结论不再具执行力;本文成为单一规范(完整覆盖 HTTP 契约/DTO/前端接线/测试)。开发以本文为准。


## 测试与验证(增量)

- 后端:`GET /sessions` 列表端点分页/过滤;`POST /sessions/{id}/commands/compact` 启动确认 + leaf 条件追加并发集成测试(events vs compact 竞争单胜);skill 流式命令(服务端解析 + SSE 同响应 + 四类错误码);Extension 注册冲突;executionMode/requiresSession/streaming 契约(⑧/继续复查⑤③);无会话端点(POST /commands/{name} 的 SESSION_REQUIRED 与 help 可达)
- 前端:resume 选择列表交互/前缀匹配;compact 启动确认 + 历史分页结果刷新(无进度条);`/skill:` 补全 + 流式消费(content-type 分流);SystemNoticeStack 渲染(⑨);无会话策略表全覆盖(⑩,含 !requiresSession 豁免)

**[继续复查⑨已采纳]** 测试清单路径统一为 `POST /sessions/{id}/commands/compact`(带 sessionId);无会话 `POST /commands/{name}` 仅 requiresSession=false 命令可用。
