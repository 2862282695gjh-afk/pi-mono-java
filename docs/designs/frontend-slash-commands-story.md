# Story 实现设计说明书：Web 前端 Slash Commands

> Story：CampusClaw Web 工作台支持 `/` 斜杠命令（发现、补全、执行）
> 关联设计：[frontend-slash-commands.md](frontend-slash-commands.md)（Active specification）、
> [frontend-slash-commands-gap-analysis.md](frontend-slash-commands-gap-analysis.md)（现状与决策过程）、
> [frontend-slash-command-low-fidelity.html](frontend-slash-command-low-fidelity.html)（低保真交互稿）
> 基线：Runtime HTTP 1.38；源码基线 `86ad27a2`（含冲突标记修复，见 §0）
> 状态：Ready for implementation

---

## 0. 开工前置（已核实的两个阻塞项）

| # | 阻塞项 | 核查结论 | 处理 |
|---|---|---|---|
| B1 | Runtime 源码残留 Git 冲突标记 | **属实**。本地 main 有 13 个 main 源文件 + 10 个测试文件含 `<<<<<<<`/`>>>>>>>`（`CampusClawApplication`、`AgentRuntimeManager`、`MateRestUtil`、`HttpMateToolClient`、`RuntimeEntryCodec` 等）；同文件在 upstream/main 全部干净——是此前合并解析的本地残留 | 已从 upstream 恢复这些文件；**恢复后全模块编译通过** |
| B2 | 无用户身份上下文 | **属实**。runtimeapi 全部端点无鉴权，无 `RuntimePrincipal`/`subjectId` 概念；`agentId` 不是身份 | 按 spec §2.2 新增（见 FS-00） |

实施顺序上必须**先做 FS-00（授权与数据归属），再做命令端点**；否则命令将运行在无身份的系统上。

---

## 1. 需求背景和价值

### 背景

CampusClaw 当前有两条用户入口：

- TUI 交互模式已被上游移除，`command/` 包遗留了宿主无关的命令骨架：`SlashCommand` SPI、非 Bean 的 `SlashCommandRegistry`（trim + 首空格分隔解析）、`SlashCommandSession` 操作端口、以及 ModelCommand/ThinkingCommand/CompactCommand/NameCommand 四个对端口编程的内置命令——该骨架自 TUI 移除后没有任何宿主启用，处于"有骨无肉连"状态；
- Web 产品工作台（Vue 3，`frontend/`）经 Runtime HTTP v1 对话（`POST /sessions/{id}/events`），但输入以 `/` 开头时被当作普通聊天文本原样发给模型。

同时 Runtime 侧已具备可复用的真实能力：配置变更端点（PUT /model、/thinking 带 If-Match）、`SessionCompactor` 手动压缩（异步 + Started/Completed/Failed 持久化事件）、Skill 目录加载体系。

### 价值

1. **产品完整性**：Web 用户获得与终端一致的会话操作入口（切模型、清会话、压缩历史），不再依赖记忆参数或页面控件位置；
2. **确定性执行**：以 `/` 开头的已注册命令由服务端确定性处理并**绝不作为普通 prompt 发给模型**——消除"想切模型却被模型回了一段话"的错误行为与无效 token 消耗；
3. **技能触达**：`/skill:<name>` 让用户直接触发已安装 Skill 并注入 SKILL.md 正文，避免用户手工描述流程；
4. **扩展生态起点**：Extension 以 `<extensionId>:<command>` 注册自定义命令，为后续插件化打基础；
5. **安全底座**：本 Story 同步引入会话授权与数据归属（owner_subject_id），所有资源访问收敛到显式授权——这是多用户化的前置。

## 2. 范围

首版包含：

- 内置命令：`/new`（CLIENT_LOCAL）、`/resume`（CLIENT_LOCAL 编排 + 新列表端点）、`/model`、`/thinking`、`/help`、`/settings`、`/compact`（SERVER）
- Skill 命名空间：`/skill:<skill-name> [arguments]`
- Extension 注册：`<extensionId>:<command>`（仅 SERVER）

不在范围：运行期安装/卸载 Extension、浏览器端 Extension handler、compact 取消端点、lease/fencing 自动恢复（多实例 compact 的自动放行——超时后保持禁止态，人工解除）。

---

## 3. 功能点分解（Feature Scenario）

### FS-00 会话授权与数据归属（P0 前置，非用户可见）

**Scenario**

```gherkin
Given 部署方提供了权威的 owner→subjectId 映射并完成存量回填
When 已认证用户访问任意 session 资源
Then 服务在校验 JWT 后由 principal.subjectId 过滤/比对归属;
And 创建会话时写入 owner_subject_id;
And 非 owner 访问返回与不存在相同的资源不可见错误;
And agentId 只能缩小结果集,不能扩大授权。
```

验收：启动扫描无悬挂冲突标记（B1 已修复，编译通过）；存量迁移后 `owner_subject_id` 无 NULL；越权访问 404 而非 403（不泄露存在性）。

### FS-01 `/` 触发命令目录发现

```gherkin
Given 已认证且已创建会话的前端工作台
When 用户在 Composer 输入首个字符 "/"
Then 浮出命令菜单（数据来自 GET /commands?sessionId=&all=true）,
    按 webCapable=true 过滤展示（TUI-only 不出现）,
    按 category 分组、按 name 过滤前缀;
When 用户继续输入 "/mo"
Then 菜单仅剩 /model。
```

验收：菜单数据一次拉取缓存；`all=true` query 在请求中可见；列表加载失败静默降级为无菜单（不阻塞聊天）。

### FS-02 命令补全与两段式确认

```gherkin
When 菜单激活
Then ArrowDown/ArrowUp 移动高亮;
And Tab 或 Enter 仅补全命令名为 "/name "（带尾空格,菜单收起,不执行）;
And Cmd/Ctrl+Enter 补全并立即执行（快捷直达）;
And Escape 关闭菜单,输入保留,焦点回到 textarea;
And menuActive 时先处理导航键,普通字符继续进入 textarea 触发重过滤。
```

验收：补全写入 `/model ` 后菜单正确收起（hasSeparator=true 语义，见解析器规范）；零候选/加载失败时 Enter 回落普通提交行为；aria-expanded/controls/activedescendant 齐全。

### FS-03 SERVER 命令执行（同步 JSON）

```gherkin
When 提交 "/model"（空参）
Then POST /sessions/{id}/commands/model 返回 200 kind=ok + 当前模型 id,
    并作为系统消息插入时间线（不持久化,不进 agent 历史）;
When 提交 "/model glm-5"
Then 前端识别别名,改走既有 PUT /model（If-Match）并刷新模型显示,
    modelChanged effect 由 changeModel 成功产生;
When 会话 streaming 中提交互斥命令
Then 返回 409,系统消息提示稍后再试。
```

覆盖命令：model/thinking 查询与 settings 摘要（requiresSession=true）。

### FS-04 CLIENT_LOCAL 本地命令豁免（/new、/resume）

```gherkin
Given 无会话（引导态）
When 提交 "/new"
Then 命令在 session 守卫之前被分流（CLIENT_LOCAL 豁免）,前端清空本页时间线,
    系统消息注明"仅清空当前视图,历史仍在服务端",零网络请求;
When 提交 "/resume"
Then 前端 GET /sessions?sessionIdPrefix=<args> 展示候选,
    选择后调用既有 App.resumeSession(sessionId),恢复后刷新命令目录;
And /resume 支持无参形式:列出候选供选择。
```

验收：CLIENT_LOCAL 分流位于 hasSession 守卫**之前**；SERVER 类命令无会话时仍提示先创建会话。

### FS-05 SERVER 命令执行（异步压缩 /compact）

```gherkin
When 提交 "/compact [instructions]"
Then POST /sessions/{id}/commands/compact 同步返回
     {kind:"ok", output:"压缩已启动", operationId}（若正在 streaming 则 409）;
And 结果由持久化 compaction entry 承载:
   COMPACTION_COMPLETED → 时间线显示 summary(system message)
   COMPACTION_FAILED(reason=CANCELLED_BY_NEW_MESSAGES) → 系统消息"因新消息取消,请重试";
And 超时未完成时准入返回 409 COMPACTION_SUSPENDED,
    该 session 进入禁止态直至运维解除（终审复查㉑决议）;
And 同一 started 至多一个终态（completed 条件追加 / failed 经 appendOperationTerminal;
    terminal 自身失败由 reconcile 兜底补偿）。
```

验收：leaf 竞争测试（events vs compact 单胜）；终态 append 异常注入 → reconcile 补偿呈现 failed 非悬挂 started；重启集成测试（悬挂 started 被识别为禁止态）。

### FS-06 `/skill:<name>` 发现与执行（SSE）

```gherkin
When 菜单输入 "/skill:"
Then 列出当前 agent 可见的全部 Skill descriptor（category=skill,streaming=true）;
When 提交 "/skill:k8s-ops --namespace prod"
Then POST /sessions/{id}/commands/skill:k8s-ops（arguments=" --namespace prod",
    content-type application/json）
    ← text/event-stream（服务端解析 SKILL.md 注入 + arguments 组装为一次 prompt,
      与手打消息同一条 events 流投影）;
And 流开始前的校验失败（SKILL_NOT_FOUND/VISIBLE/INVOCATION_DISABLED/PATH_INVALID）
    返回 JSON 错误信封——前端按响应 content-type 分流解析。
```

验收：四类稳定错误码 + i18n + friendlyError 映射；skill 正文截断上限生效；流式输出进对话时间线。

### FS-07 Extension 命令（SERVER）

```gherkin
Given 一个 Extension 声明 extensionId="jira" 且注册 jira:create
When 菜单输入 "/jira:"
Then 出现 jira:create descriptor(category=extension);
When 提交执行
Then 行为等同 SERVER 命令(可选 streaming/requiresSession),
    descriptor 带 sourceExtensionId="jira" 仅诊断用。
```

验收：Extension 声明 CLIENT_LOCAL → 启动期拒绝（横向㉒）；namespace 冒用 → 启动期拒绝（横向㉗）。

### FS-08 `/events` Slash 守卫（正确性兜底）

```gherkin
When 直接 POST /events body {"message":"/model glm-5"}（绕过前端）
Then 返回 400 COMMAND_NOT_ROUTED,该消息绝不发给模型;
When body {"message":"  /model glm-5"}（前导空白）
Then 同样 400（守卫用共享 parseSlashInput,容忍前导空白）;
When body {"message":"/abc"}（未注册）
Then 正常透传给模型（与"/"+普通文本不可区分时保持宽容）。
```

### FS-09 命令目录的两层作用域

```gherkin
When GET /commands（无 sessionId 或 scope=static）
Then 仅静态命令(内置+Extension);不含任何 skill;
When GET /commands?sessionId=<已有会话>
Then 返回静态命令 + 该 agent 可见的 Skill descriptor
    （请求期从该 agent runtime 目录解析,首版不做跨会话缓存）;
And A/B agent 的 skill 集互相隔离。
```

---

## 4. 功能实现思路

总体分层：**目录静态层（Catalog）/会话动态层（Skill 解析）/执行分发（Invoker）/传输（JSON 与 SSE 双形态）**,前端只消费 HTTP 契约并在 composer 上叠菜单交互；所有 `/`-判定规则前后端共享同一 parser 语义与锁定用例集。

关键取舍（承八轮评审决议）：

1. **原子工具组**：list/call 两 Mate 命令作为整体注入——过滤后只剩其一则整组不注入;
2. **CLIENT_LOCAL 豁免 session 守卫**:new/resume 的分流在 `App.submit()` 的 `hasSession` 守卫**之前**;
3. **别名优先**:model/thinking 写操作不经命令端点,由前端直调既有 PUT 端点继承 If-Match;命令端点对这两者带参一律 400 引导;
4. **operation 状态机**(compact):started(准入事务内,记 expectedLeafId)→completed 条件追加(防 summary 覆盖新消息)/failed 经 appendOperationTerminal(CAS 失败转投);CAS 失败晚于 HTTP 返回→failed entry 呈现于历史;单飞标记以终态回调释放;超时禁止态持久化为 SUSPENDED entry,解除仅运维显式或单实例 future 终止的可验证确认;
5. **skill 三态错误码**:UNKNOWN(不在 Catalog)/NOT_VISIBLE/DISABLED 分别稳定返回,映射统一异常处理器。

## 5. 前端设计

低保真：[frontend-slash-command-low-fidelity.html](frontend-slash-command-low-fidelity.html)。落地组件与状态接线如下。

### 5.1 文件清单

| 文件 | 动作 | 内容 |
|---|---|---|
| `types/runtime.ts` | 追加 | `SlashCommandDescriptor`(含 executionMode/requiresSession/streaming/sourceExtensionId)、`SlashCommandResult`(kind/output/effects)、`SlashMatch` 三态联合类型 |
| `composables/useSlashCommands.ts` | 新建 | 目录缓存(load(all))/matchCommand/execute 的纯逻辑封装；effects 分发回调注入 |
| `components/CommandMenu.vue` | 新建 | 受控浮层(props: visible/filter/activeIndex/commands) |
| `components/ComposerBox.vue` | 修改 | onKeydown/updateText 集成菜单导航与两段式提交 |
| `App.vue` | 修改 | slash composable 装配、runCommand 接线、systemMessages 渲染 |

### 5.2 ComposerBox 键盘语义（定稿）

```
menuActive 时（按序判定，命中即 return）：
  ArrowDown/Up        moveActive(±1)
  Cmd/Ctrl+Enter      补全并 emit commandSubmit(completeAndRun)
  Tab 或 Enter        仅补全为 "/name "（completeActive，两段式第一段）
  Escape              关闭菜单
其余键放行 textarea 由 updateText 重过滤

updateText 中：
  const parsed = parseSlashInput(textarea.value)
  menuActive = !props.running && parsed !== null && !parsed.hasSeparator
               && commands.value.length > 0          // 加载失败静默降级
```

要点（对应已踩过的坑）：**激活依据是 hasSeparator 而非 arguments 是否为空**——否则补全写入的尾空格会让菜单永不收起；Cmd/Ctrl+Enter 判定必须在普通 Enter 之前，否则直达路径永不可达。

### 5.3 useSlashCommands.execute 分流（伪代码）

```
execute(name, args):
  if name === 'new':            # CLIENT_LOCAL
      emitConversationReset(); return ok('已开始新对话')
  if name === 'resume':         # CLIENT_LOCAL 编排
      list = GET /sessions?sessionIdPrefix=args; 渲染候选/恢复
  if catalog Static(def) 且 def.streaming:
      return fetchStream(POST .../commands/{name})         # skill:/流式 Extension
  if staticDef 且 executionMode==SERVER:
      alias = ALIAS.get(name)                    # model/thinking 写操作
      if alias: await runtime.<alias>(args)      # 继承 If-Match；412 保留草稿
                return ok(aliasFeedback)
      else: return requestResult(POST .../commands/{name})   # JSON
```

### 5.4 App.vue 接线

- `submit()` 最前面插入 slash 三态分流（详见实现手册 6.x 分流顺序图）：executable → execute；web-reserved → 本地提示；unknown → 普通
- CLIENT_LOCAL 在 `!hasSession` 时也放行（new 清视图/resume 引导列表）；有会话时照常
- `systemMessages: {id,text,isError,at}[]` 本地数组驱动 `SystemNoticeStack`（无会话 welcome 分支顶部也有渲染位）；不持久化（刷新消失 = D4 决议）

### 5.5 视觉与可达性

- 菜单浮层定位 bottom:100%，宽度齐 composer；`role=listbox/option/aria-selected`
- textarea `aria-expanded/aria-controls/aria-activedescendant`；Esc 后焦点回 textarea
- 系统消息居中灰字（error 红）、与 accepted-list 视觉层级一致；reduced-motion 下无动效

---

## 6. 代码设计（后端）

### 6.1 包结构与类清单（新增 ★ / 修改 ✎）

```
coding-agent-cli/src/main/java/com/campusclaw/codingagent/
├── runtimeapi/command/                        ★ 新包：HTTP 命令子系统
│   ├── WebCommandCatalog.java                 ← 目录+parser+isRegistered(唯一来源)
│   ├── WebCommandDefinition.java              ← record + ExecutionMode 枚举 + compact ctor 校验
│   ├── WebCommandEffect.java                  ← effects 常量(modelChanged/conversationReset/…)
│   └── WebCommandInvoker.java                 ← 执行入口(SERVER handler 分发/SSE attach)
├── runtimeapi/dto/
│   ├── CommandDescriptorDTO.java              ★
│   ├── CommandResultDTO.java                  ★ (kind/output/operationId/effects)
│   └── CommandInvocationRequestDTO.java       ★ (arguments/clientRequestId)
├── runtimeapi/web/
│   └── RuntimeCommandController.java          ★ GET /commands + POST 两条
└── runtimeapi/event/RuntimeEventService.java  ✎ 提交守卫(parseSlashInput→COMMAND_NOT_ROUTED)

frontend/src/                                   ← 前端清单见 §5.1
```

### 6.2 关键约束与不变量

| 不变量 | 保证机制 |
|---|---|
| 已识别命令绝不作为普通 prompt | events 守卫(服务端)+composer 分流(客户端)，双保险 |
| 每 session 同时至多一个 compact operation | 准入+登记同一行锁事务（16）；afterCommit 启动（17） |
| 每个 started 恰一个终态 | completed=条件追加 / failed=appendOperationTerminal(19)；append 异常→14 reconcile 兜底 |
| skill 参数不可变 | parseSlashInput 快照深拷贝+逐层 unmodifiable |
| skill 必经服务端解析 | grammar 路由不看静态表，per-session SkillResolver 独占处置 |
| Extension namespace 所有权 | extensionId 强校验+唯一拥有（27） |

### 6.3 失败恢复矩阵（compact）

| 失败点 | 表现 | 补偿 |
|---|---|---|
| 准入发现超时 started | 同事务补 failed(TERMINAL_WRITE_RECOVERY)+拒绝 | reconcile 启动扫描同逻辑 |
| completed 条件追加 CAS 失败 | 转 failed(entry) 无条件追加 | — |
| failed/completed entry 写入本身失败 | 标记保留；reconcile 任务补终态 | SessionCleanupWorker 形态周期任务 |
| skill 参数非法 | 稳定 400 错误码(SKILL_*) | 前端 friendlyError 映射 |

### 6.4 复用边界

- `command/SlashCommand*`（TUI 遗产）：名称/文案/业务校验可移植；parser 与 Context 不得直用（Register 非 Bean；语义差异：trim/空白分隔 vs hasSeparator）
- `SessionCompactor.compact()`：真实能力，经重建会话路径接入
- `clearSessionView()`：useRuntimeApi 既有方法，/new CLIENT_LOCAL 复位复用
- 既有 PUT /model //thinking：别名目标，If-Match 一致性继承

---

## 7. 接口设计

### 7.1 目录

| 方法/路径 | 说明 |
|---|---|
| `GET /campusclaw-service/v1/commands` | 静态命令(内置+Extension)；未认证行为同现有 |
| `GET /campusclaw-service/v1/commands?sessionId=` | 合并该会话可见 skill |

Descriptor 字段：name/description/argsHint/category(session|conversation|system|skill|extension)/executionMode(SERVER\|CLIENT_LOCAL)/requiresSession/streaming/sourceExtensionId。

### 7.2 执行（两条路径共用 Invoker 与 DTO）

| 场景 | 端点 | 成功响应 | 错误 |
|---|---|---|---|
| 有会话命令 | `POST /sessions/{sessionId}/commands/{name}` | json `CommandResultDTO`(kind/output/**operationId**/effects) | 400 INVALID_* / 409 STREAMING / 500 |
| 无会话 SERVER 命令(help 等 requiresSession=false) | `POST /campusclaw-service/v1/commands/{name}` | json `CommandResultDTO` | 409 streaming / 500 |

### 7.3 请求体（共通）

```json
{ "arguments": "…", "clientRequestId": "uuid-可省略" }
```

- arguments ≤ 8KB UTF-8、原样保留空白/换行；缺字段视为 ""
- 非 string 类型 400 `ARGUMENTS_TYPE_INVALID`

### 7.4 `/events` 守卫行为变更

| 消息形如 | 响应 |
|---|---|
| "/" 后首 token ∈ Catalog（含 skill:*，不分 webCapable） | 400 `COMMAND_NOT_ROUTED` |
| "/" 但不匹配 Catalog | 正常透传 |
| `  /model …`（前导空白） | 400 COMMAND_NOT_ROUTED（守住 '/  model' 绕过） |

### 7.5 错误码（需进 RuntimeErrorCode/i18n/friendlyError）

`COMMAND_NOT_ROUTED` / `COMMAND_NOT_AVAILABLE_ON_WEB` / `COMMAND_CLIENT_LOCAL` / `ARGUMENTS_TOO_LARGE` / `SESSION_REQUIRED` / `COMPACTION_IN_PROGRESS` / `COMPACTION_SUSPENDED` / `MODEL_WRITE_VIA_ALIAS` / `SKILL_NOT_FOUND` / `SKILL_NOT_VISIBLE` / `SKILL_INVOCATION_DISABLED` / `SKILL_PATH_INVALID` / `OPERATION_TERMINAL_WRITE_RECOVERED`（内部）/ `TERMINAL_WRITE_RECOVERY`(运维面)

### 7.6 streaming 响应

SSE 命令(`streaming=true`)在同一 POST 响应中以 `text/event-stream` 返回流式输出——**单个连接承载完整流,前端仅发一次请求**；禁止“服务端启动任务+要求前端再开另一条订阅”的双请求形态(重复 prompt 陷阱)。断线重连沿用历史分页。

---

## 8. 测试汇总

- 后端:`RuntimeCommandControllerTest`(默认/all 响应与错误分支)、`WebCommandCatalogTest`(parseSlashInput 锁定用例+注册冲突)、`WebCommandInvokerTest`(各命令参数分支)、`RuntimeEventServiceTest` 增补守卫、`Extension/SkillResolver` 路径测试、entry 写入失败注入测试(14)
- 前端:`useSlashCommands.test.ts`(三态/别名/ETag)、`CommandMenu.test.ts`(过滤/a11y/键盘)、App.submit 分流测试(CLIENT_LOCAL 豁免)
- 兼容:`parseSlashInput` 共享用例 JSON 前后端一致性 CI 校验

## 验证

```bash
./mvnw -pl :campusclaw-coding-agent -am test -DskipTests=false
cd frontend && npm test && npm run typecheck
# 静态检查
./mvnw spotless:check checkstyle:check
```

## 版本历史

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-08-25 | 设计 v1—v3 | 八轮评审批注①—⑧（目录唯一源/别名/两段式等） |
| 2026-08-26 | 评审⑤—⑪ | compact 异步语义逐轮收敛(operation 状态机雏形) |
| 2026-08-27 | v4+终审系列 | toolId→toolName 别名、缓存生命周期四连问、终态双围栏、suspended 持久化、复盘五批注 |
| 2026-08-27 | 本 Story | 上述设计的实施拆解版本;开工前置 B1 已在本仓修复,B2 随 FS-00 实现 |
