# 前端 Slash Commands 功能设计文档

> 模块:`coding-agent-cli`(runtimeapi) + `frontend/`
> 状态:Proposed
> 日期:2026-08-25(v2),2026-08-26(v3 评审修订:采纳 codex 批注①—⑧)
> 契约基线:Runtime HTTP 1.38(`BASE_PATH = /campusclaw-service/v1`);本设计的契约改动为 **1.39 候选**,落地前需与 owner 对齐
> 读者:前端(Vue)与后端(Java runtimeapi)开发者,按本文可直接开工

---

## Context(为什么)

TUI 交互模式已具备完整斜杠命令体系(`command/` 包:`SlashCommandRegistry` + 27 个内置命令),由 `InteractiveMode:621` 拦截以 `/` 开头的输入分发。Web 前端(CampusClaw 产品工作台)走 Runtime HTTP v1 链路:

```
ComposerBox → App.submit() → useRuntimeApi.sendMessage(message)
  → POST /sessions/{sessionId}/events  body: {message}
    → RuntimeEventController.submit → RuntimeEventService → agent.prompt(message)
```

链路中无任何命令拦截:用户输入 `/model glm-5` 会作为聊天文本发给模型。本设计把命令能力以产品化方式延伸到 Web 前端,**本文写到文件与函数级别,前后端可并行开发**。

## 关键定义

| 名称 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `CommandDescriptorDTO` | Java DTO | `runtimeapi/dto` | 命令元数据 |
| `CommandResultDTO` | Java DTO | `runtimeapi/dto` | 执行结果 + effects 信号 |
| `RuntimeCommandController` | REST | `runtimeapi/web` | 列表/执行两个端点 |
| `WebCommandInvoker` | Java 服务 | `runtimeapi/command` | Web 侧命令执行(不复用 TUI 的 SlashCommandContext) |
| `SlashCommandDescriptor` | TS 类型 | `frontend/src/types/runtime.ts` | 命令元数据镜像 |
| `SlashCommandResult` | TS 类型 | `frontend/src/types/runtime.ts` | 结果镜像 |
| `useSlashCommands` | composable | `frontend/src/composables/useSlashCommands.ts` | 列表缓存 + 执行 + effects 分发 |
| `CommandMenu.vue` | 组件 | `frontend/src/components/CommandMenu.vue` | 补全浮层 |
| 系统消息 | 前端概念 | `ConversationTimeline` | 命令结果在时间线的展示形态 |

---

## 第一部分:HTTP 契约(前后端共同依据)

### 1.1 `GET /campusclaw-service/v1/commands`

响应(`ResultBean<List<CommandDescriptorDTO>>`,复用现有信封):

```json
{
  "code": "0",
  "message": "ok",
  "result": [
    {
      "name": "model",
      "description": "查看或切换当前模型",
      "argsHint": "[model-id]",
      "category": "session",
      "executionMode": "SERVER",
      "webCapable": true
    },
    {
      "name": "hotkeys",
      "description": "TUI 快捷键列表",
      "argsHint": "",
      "category": "tui",
      "executionMode": "SERVER",
      "webCapable": false
    }
  ]
}
```

字段语义:
- `name`:命令名,**不含斜杠**;`^[a-z][a-z0-9-]*$`(与现有命令名一致)
- `argsHint`:参数提示给补全菜单展示(如 `[model-id]`),可为空串
- `category`:`session` | `conversation` | `system` | `tui`——前端菜单分组
- `webCapable`:false 时前端菜单不展示;`all=true` 返回全集(首版正式契约,三态 matcher 数据源,见下方复审①决议)

**默认只返回 `webCapable=true` 的命令**。无需会话上下文(命令集是进程级静态数据)。

**[复审①已采纳] `all=true` 升级为首版正式契约**:不带参数只返回 `webCapable=true`(兼容仅需菜单的调用方);`all=true` 返回全集——**三态 matcher 的数据源**(区分 web-reserved 与 unknown 必需,非诊断用途)。调用方:已认证的 Runtime API 使用者,与其它端点同权限;返回幂等静态,无副作用。


**[批注①已采纳] 唯一目录源 = `WebCommandCatalog` Bean**:新增 Spring Bean `WebCommandCatalog`(见 2.1/2.2)持有**全部**已知命令(含 webCapable=false 的 TUI 专属项),它同时是菜单列表、前端 `matchCommand` 对照集、`/events` 守卫、`/commands/{name}` 执行端点的**唯一来源**——不存在第二份注册表。批注指出的"菜单只认子集、守卫认全量"断路由同源消除:TUI-only 命令(如 `/hotkeys`)在 events 被拒后,`POST /commands/{name}` 返回**稳定的 `COMMAND_NOT_AVAILABLE_ON_WEB`(400)**而非 404,前端据此提示"该命令仅在终端可用"。三态语义:

| 输入 | 菜单 | events 守卫 | commands 执行 |
|---|---|---|---|
| `/model`(webCapable) | 展示 | 拒 → 引导命令端点 | 执行 |
| `/hotkeys`(TUI-only,在 Catalog) | 不展示 | 拒 | 400 COMMAND_NOT_AVAILABLE_ON_WEB |
| `/abc`(不在 Catalog) | 不展示 | **透传给模型** | 404(正常未知) |


### 1.2 `POST /campusclaw-service/v1/sessions/{sessionId}/commands/{name}`

请求:

```json
{ "arguments": "glm-5" }
```

- `arguments`:去掉命令名后的原始参数串(可为空串);`name` 路径段需过 `^[a-z][a-z0-9-]*$` 校验

响应(`ResultBean<CommandResultDTO>`):

```json
{
  "code": "0",
  "message": "ok",
  "result": {
    "kind": "ok",
    "output": "当前模型: glm-5",
    "effects": {}
  }
}
```

`kind` 三值:
- `ok`:成功,`output` 为给用户看的反馈文本
- `error`:命令业务失败(如未知模型名),`output` 为安全摘要
- `no-session`:理论上不会出现(路径已含 sessionId),保留作防御

`effects` 首版实际产生的键(前端逐个处理,未知键忽略——向前兼容;完整归属表见 3.6):

| 键 | 类型 | 产生来源 | 前端动作 |
|---|---|---|---|
| `modelChanged` | boolean | 别名路径(经 changeModel 真实写入,服务端持久化) | 刷新会话模型显示 |
| `thinkingChanged` | boolean | 别名路径(经 changeThinking,服务端持久化) | 刷新思考级别显示 |
| `conversationReset` | boolean | `/new` 命令(客户端本地清视图) | 清空本页时间线 |
| ~~sessionRenamed~~ | — | 首版已移出(/name 削减,见批注④) | — |
| ~~historyCompacted~~ | — | 首版已移出(/compact 削减) | — |

**[批注②已采纳] `/model`、`/thinking` 写操作走别名,不经命令端点**:这两个命令**带参数时在前端解析为别名**——`useSlashCommands.execute` 直接调用现有 `runtime.changeModel(modelId)` / `runtime.changeThinking(bool)`(内部已带 `If-Match` + 返回最新 Session 与 ETag,乐观并发保护完整继承),成功后组装等效 `SlashCommandResult` 走统一结果展示。命令端点只承接**无对应既有端点**的命令。后端兜底:命令端点收到 model/thinking 写参数时返回 400 提示"请经 /model 端点执行",防止绕过前端造成双路径漂移。空参查询仍走命令端点(只读无并发问题)。


**同步短请求,无 SSE**。HTTP 状态码:200 成功(kind=ok/error 都可能是 200);404 未知命令名(不在 Catalog);400 无效参数格式 / TUI-only 命令(COMMAND_NOT_AVAILABLE_ON_WEB) / model/thinking 带参(引导别名);409 会话 streaming 中执行互斥命令;500 内部错误(信封 `message` 给安全摘要)。

### 1.3 `POST …/events` 行为变更

`RuntimeEventService.submit` 增加守卫:消息以 `/` 开头**且**首 token 匹配 `WebCommandCatalog` 中的命令名(全集,不区分 webCapable)时,返回错误码 `COMMAND_NOT_ROUTED`(400),message 提示"命令请经 /commands 端点执行"。不匹配任何命令名的 `/xxx` 正常透传(与 TUI `execute() 返回 false` 语义一致)。

**[批注③已采纳]** 守卫数据源即 `WebCommandCatalog`(Spring Bean,Runtime API 显式注入),不依赖 `SlashCommandRegistry`(其非 Bean 且 Runtime Host 不启用该解析)。解析规则**写死为共享规范**,前后端各自实现、同一组用例锁定:

```
parseSlashInput(text):
  trimmed = text 去除首尾空白(含换行)
  若 trimmed 不以 '/' 开头 → null
  rest = trimmed.substring(1);去除前导空白(容忍 '/ model')
  首个空白(空格/制表/换行,≥1)之前 = name;其后去除前导空白 = arguments
  name 为空(输入仅 '/')→ null
```

锁定用例(前后端跑同一组,结果必须一致):`'/model glm-5'`→(model,glm-5) / `'/model'`→(model,'') / `'/  model   x  '`→(model,'x') / `'/model\n第二行'`→(model,'第二行') / `'/'`→null / `'/model '`→(model,'') / `'text /model'`→null(非开头) / `'/中文命令'`→(中文命令,'')(未命中 Catalog 即透传)。

**[再复审②已采纳] 解析结果增加 `hasSeparator` 字段,菜单激活改依据分隔符**。批注属实:parser 对输入做 trim,`'/model '`(补全写入的尾空格)与 `'/model'` 的 `arguments` 都是 `''`——按 `arguments === ''` 判激活会导致补全后菜单不收起、Enter 再次补全的死循环。修订 `parseSlashInput` 返回结构:

```
ParsedSlashInput { name, arguments, hasSeparator }
  hasSeparator = 原始输入(未 trim)中 name 之后是否出现过空白分隔符
```

- 菜单激活 = `parsed !== null && !parsed.hasSeparator`(名字还在输入中,无分隔符)
- 补全写入 `/model ` → hasSeparator=true → 菜单收起 → 普通 Enter 走 submit 分流(两段式成立)
- 锁定用例追加:`'/model '`→(model,'',hasSeparator=true)、`'/model'`→(model,'',false)、`'/model	x'`→(model,'x',true)——前后端 matcher/菜单共用此字段


---

## 第二部分:后端实现(Java)

### 2.1 文件清单

```
modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/
├── command/
│   ├── WebCommandCatalog.java          ← 新增【批注①采纳】:唯一命令目录(Spring Bean,
│   │                                     含 webCapable 全集 + parseSlashInput/isRegistered)
│   ├── WebCommandInvoker.java          ← 新增:执行入口(依赖 Catalog)
│   ├── WebCommandDefinition.java       ← 新增:命令定义 record
│   └── WebCommandEffect.java           ← 新增:effects 常量/record
├── dto/
│   ├── CommandDescriptorDTO.java       ← 新增
│   └── CommandResultDTO.java           ← 新增
└── web/
    └── RuntimeCommandController.java   ← 新增
修改:runtimeapi/event/RuntimeEventService.java(/守卫,注入 Catalog)
修改:runtimeapi/error(注册 COMMAND_NOT_ROUTED / COMMAND_NOT_AVAILABLE_ON_WEB)
```

### 2.2 `WebCommandDefinition`(命令注册表,不走 SlashCommandRegistry)

```java
public record WebCommandDefinition(
        String name,
        String description,
        String argsHint,
        String category,
        ExecutionMode executionMode,   // SERVER | CLIENT_LOCAL(再复审③)
        BiFunction<CommandExecutionContext, String, CommandOutcome> handler) {  // SERVER 必填,CLIENT_LOCAL 为 null

    public enum ExecutionMode { SERVER, CLIENT_LOCAL }
}

// CommandExecutionContext: sessionId + RuntimeSessionService(持久化会话读取),
// 不持有 RuntimeSessionHolder(idle 会话无 Holder,见复审②)。
```

**[终审①已采纳] 执行模式约束落进 compact constructor**(注释约定不阻止错误注册):

```java
public record WebCommandDefinition(
        String name, String description, String argsHint, String category,
        ExecutionMode executionMode,
        BiFunction<CommandExecutionContext, String, CommandOutcome> handler) {

    public enum ExecutionMode { SERVER, CLIENT_LOCAL }

    public WebCommandDefinition {
        // SERVER + null handler → 执行期 NPE;CLIENT_LOCAL + handler → 职责不清。
        // 注册期即拒绝,约束成为可执行契约。
        // 合法组合: (SERVER, handler!=null) 或 (CLIENT_LOCAL, handler==null),
        // 即两者布尔值相等;写为 != 并取反抛错(终审复查①:上一版 == 方向写反,
        // 会放行错误组合、拒绝正确组合)。
        boolean isServer = executionMode == ExecutionMode.SERVER;
        boolean hasHandler = handler != null;
        if (executionMode == null || isServer != hasHandler) {
            throw new IllegalArgumentException(
                "SERVER requires a handler; CLIENT_LOCAL requires null handler: " + name);
        }
    }
}
```

**[终审复查①已采纳]** 上一版 `(executionMode == SERVER) == (handler != null)` 方向写反(== 拒绝正确组合、放行错误组合);已改为命名布尔 `isServer != hasHandler` 抛错并加注释说明真值表,同时补四组合参数化测试。



**[再复审③已采纳] record 签名统一 + local-only 描述符显式化**:
- 签名统一为 `BiFunction<CommandExecutionContext, String, CommandOutcome>`(第二参数 = arguments,保留 BiFunction;复审②正文的"单参数 Function"表述作废)
- `/new` 等纯前端命令不从该 record 虚设 handler:`WebCommandDefinition` 增加 `executionMode` 字段——`SERVER`(handler 必填)/ `CLIENT_LOCAL`(handler 为 null,后端 `/commands/{name}` 收到直接返回 400 提示 client-local command);三态 matcher 与菜单照常把它当 executable,execute 在前端本地处理,零网络请求


**[复审②已采纳] handler 上下文改为持久层,不依赖 `RuntimeSessionHolder`**。批注属实:`RuntimeSessionEngineRegistry` 类注释明确"不缓存 idle Session"、Holder 随执行结束释放——而命令恰在非 running 态执行,届时 Holder 已不可用。修订:

- `WebCommandDefinition.handler` 签名为 `BiFunction<CommandExecutionContext, String, CommandOutcome>`(第二参数 = arguments;终审②统一,以再复审③ record 定义为准),`CommandExecutionContext` 携带 `sessionId` + `RuntimeSessionService`(现有持久化会话服务,`RuntimeSessionConfigurationService` 同款读取路径)
- `model`/`thinking` 空参查询:从会话存储读(`requireSession(sessionId)`),不经 Holder
- `/new`:**降级为纯前端动作**——不出现在命令端点注册表(matchCommand 三态仍判定为 executable,但 execute 直接本地清视图,零网络请求);注册表实际后端命令只剩 `model`/`thinking`(查询) + `help`/`settings`

**为什么不复用 `SlashCommandRegistry`**:TUI 命令的 `SlashCommandContext` 携带 `AgentSession + OutputWriter`(终端语义),27 个命令大量直接操作 TUI 状态;Web 侧上下文是 `CommandExecutionContext`(持久层会话读取,见复审②)。硬桥接需要重写所有命令的上下文适配,不如为新语义建独立注册表,首版只注册 Web 能做的 5 个命令(见下表;model/thinking 写操作走别名,注册表内仅空参查询),handler 内部**复用底层会话操作方法**。

**[终审②已采纳]** 复审②决议段的 `Function` 单参表述与 `RuntimeSessionHolder` 上下文残留已清理(统一为 `BiFunction<CommandExecutionContext, String, CommandOutcome>` + `CommandExecutionContext`);Controller 骨架 `invoke(holder, ...)` 同步改为传入 `CommandExecutionContext`。


`WebCommandCatalog` 注册表(**首版 5 个**,与批注④决议一致;model/thinking 的写操作走前端别名不经此端点,见 1.2):

| name | argsHint | handler 要点(经 `CommandExecutionContext` 读持久化会话,复审②) |
|---|---|---|
| `model` | `[model-id]` | **仅空参查询**:`requireSession(sessionId)` 读模型 id;带参 400 引导别名(见 1.2) |
| `thinking` | `[on\|off]` | **仅空参查询**:读思考级别;带参同样 400 |
| `new` | — | **CLIENT_LOCAL**(复审②+再复审③):executionMode=CLIENT_LOCAL、handler=null;后端收到该 name 返回 400 提示 client-local;前端 execute 本地清视图,零网络请求 |
| `help` | — | 输出 webCapable 命令列表文本(无需会话) |
| `settings` | — | 只读:当前模型/思考级别摘要(持久层读取) |

(Catalog 同时登记 webCapable=false 的 TUI 专属命令名——只为守卫与三态判定,不可执行,不出现在菜单;历史版本曾含 name/compact/export,削减理由见批注④表。)

**[批注④已采纳] 首版命令削减为 5 个**:`model` / `thinking` / `new` / `help` / `settings`。移出项及理由:

| 移出命令 | 理由(采纳批注) | 重开条件 |
|---|---|---|
| `/compact` | Runtime 无 Web 可调用的手动压缩操作,`historyCompacted` 不能表示未发生的成功 | 服务端暴露真实压缩端点且可观测后 |
| `/name` | 会话标题仅存前端内存 `threads`,TUI `NameCommand` 自身也标注待 mate-service 适配;`sessionRenamed` 刷新的只是本地数组 | 标题持久化 API 落地后 |
| `/export` | 全量历史同步拼 JSON 无体积/超时/编码/文件名/下载安全边界 | 改造为受限下载端点(流式 + 大小上限)或仅导出前端已加载历史,另行设计 |

**[新增需求已受理]** 范围扩展(/resume、/compact 真实压缩、/skill: 执行、Extension 注册)的完整设计、当前前后端实现 gap 逐项对照、以及对本文既有结论的修订清单,见 **[frontend-slash-commands-gap-analysis.md](frontend-slash-commands-gap-analysis.md)**(含:目录动态化、/compact 打破同步约束走 SSE 进度、会话列表新端点、skill 命名空间、Extension 注册冲突规则、无会话策略统一表)。本文的 5 命令静态目录与同步约束结论以该文档为准。


`/new` 的 `conversationReset` 语义同步收窄(见 3.6 effects 标注)。

`CommandOutcome`:

```java
public record CommandOutcome(String kind, String output, Map<String, Boolean> effects) {
    public static CommandOutcome ok(String output) { ... }
    public static CommandOutcome ok(String output, String... effectKeys) { ... }
    public static CommandOutcome error(String message) { ... }
}
```

### 2.3 `RuntimeCommandController` 骨架

```java
@RestController
@RequestMapping(RuntimeApiConstants.BASE_PATH)
public class RuntimeCommandController {

    @GetMapping("/commands")
    public ResultBean<List<CommandDescriptorDTO>> list(
            @RequestParam(defaultValue = "false") boolean all) { ... }
    // all=false(默认)只回 webCapable 项;all=true 回全集(终审复查⑤)

    @PostMapping("/sessions/{sessionId}/commands/{name}")
    public ResultBean<CommandResultDTO> execute(
            @PathVariable @NotBlank @Pattern(regexp = SESSION_ID_REGEX) String sessionId,
            @PathVariable @Pattern(regexp = "^[a-z][a-z0-9-]*$") String name,
            @RequestBody CommandExecuteRequestDTO body) {
        // 404: 未注册命令名(异常处理器映射)
        // 409: 会话 streaming 且命令标记互斥(model/thinking/name)
        // 其余: invoker.invoke(new CommandExecutionContext(sessionId, sessionService), name, body.arguments())
    }
}
```

**[终审复查⑤已采纳]** `list()` 补 `@RequestParam(defaultValue = "false") boolean all`;测试同时覆盖默认响应(仅 webCapable)与 `?all=true` 全量响应。


### 2.4 `/events` 守卫(RuntimeEventService)

```java
// submit() 入口、校验 message 之后(终审③:与 1.3 共享解析,勿手写 startsWith/firstToken):
var parsed = catalog.parseSlashInput(message);
if (parsed != null && catalog.isRegistered(parsed.name())) {
    throw new RuntimeApiException(RuntimeErrorCode.COMMAND_NOT_ROUTED, parsed.name());
}
```

**[终审③已采纳]** 守卫骨架改用 `catalog.parseSlashInput(message)` + `isRegistered(parsed.name())`(与 1.3 共享规范,含前导空白容忍)——原始 `startsWith("/") + firstToken` 写法会使 `'  /model'` 这类前端可识别的输入绕过守卫直达模型,前后端不一致。守卫回归测试追加:`'  /model'`、`'/  model x'`(前导空白与多空白形式)。


`COMMAND_NOT_ROUTED` 加入 `RuntimeErrorCode`,HTTP 映射 400。

### 2.5 后端测试(清单)

- `RuntimeCommandControllerTest`(MockMvc 或现有测试风格):
  - `GET /commands`(默认)只含 webCapable;`?all=true` 含全集(终审复查⑤)
  - `POST /commands/model`(空参)→ 200 + kind=ok + 当前模型 id
  - `POST /commands/model glm-5`(有效参)→ **400 + 稳定引导错误码(如 MODEL_WRITE_VIA_ALIAS)/消息提示走 PUT /model**——不再断言命令端点写入成功(终审④:modelChanged 的成功路径由 PUT /model 既有测试与 useSlashCommands 别名测试覆盖)
  - **`WebCommandDefinition` 四组合参数化测试(终审复查①)**:(SERVER, handler) 与 (CLIENT_LOCAL, null) 注册成功;(SERVER, null) 与 (CLIENT_LOCAL, handler) 抛 IllegalArgumentException
  - `POST /commands/unknown` → 404
  - `POST /commands/model` 于 streaming 会话 → 409
  - 无效 name 路径段(`/commands/Model`)→ 400

**[终审④已采纳]** 上一行"(有效参)→ effects.modelChanged=true"是 v2 遗留的双写路径断言,已改写为 400 + 稳定引导错误码;`modelChanged` 成功用例归属 PUT /model 既有测试与前端别名测试。

- `RuntimeEventServiceTest` 增补:
  - `/model x` 走 events → 400 COMMAND_NOT_ROUTED
  - `/非命令文本` → 正常透传(现有断言不破坏)

---

## 第三部分:前端实现(Vue)

### 3.1 类型(`types/runtime.ts` 追加)

```ts
export interface SlashCommandDescriptor {
  name: string;
  description: string;
  argsHint: string;
  category: 'session' | 'conversation' | 'system' | 'tui';
  executionMode: 'SERVER' | 'CLIENT_LOCAL';   // 终审复查⑧:进 TS 类型,分流依据
  webCapable: boolean;
}

export interface SlashCommandResult {
  kind: 'ok' | 'error' | 'no-session';
  output: string;
  effects: Partial<{
    modelChanged: boolean;
    thinkingChanged: boolean;
    sessionRenamed: boolean;
    conversationReset: boolean;
    historyCompacted: boolean;
  }>;
}
```

**[终审复查⑧已采纳]** `executionMode` 补进 CommandDescriptorDTO(含 GET 响应示例两处)与 TS `SlashCommandDescriptor`;matcher 契约测试断言该字段(SERVER/CLIENT_LOCAL 各出现在默认与 all=true 响应中)。


### 3.2 `useSlashCommands.ts`(新建 composable,约 120 行)

```ts
export function useSlashCommands(runtime: ReturnType<typeof useRuntimeApi>) {
  const commands = ref<SlashCommandDescriptor[]>([]);
  const loaded = ref(false);
  const executing = ref(false);

  async function load(force = false): Promise<void> {
    if (loaded.value && !force) return;
    // 必须请求 GET /commands?all=true(终审复查②:三态 matcher 依赖全集,
    // 默认响应只含 webCapable 项会让 /hotkeys 被判 unknown 错误透传)
    // 走 useRuntimeApi 同源的 requestRaw/错误处理;
    // 失败静默降级:commands 保持空,输入 '/' 无菜单(不阻塞主流程)
  }

  /** 解析输入为三态(仅非 running 态拦截;running 态 steer/queue 语义不变)。
   *  parseSlashInput 与后端 WebCommandCatalog 同规则(1.3 锁定用例)。 */
  function matchCommand(text: string): SlashMatch {
    // executable:webCapable=true 且首 token 命中 → 命令执行
    // web-reserved:webCapable=false 且命中 → 本地提示,零网络请求
    // unknown:未命中(含非 '/' 开头)→ 透传普通消息
  }

  /** 执行命令并分发 effects;返回结果供调用方插入系统消息 */
async function execute(name: string, args: string): Promise<SlashCommandResult> {
    // 【别名分流(批注②)】model/thinking 带参时直接走既有端点(If-Match 完整):
    //   model+参数   → await runtime.changeModel(args) → 组装 {kind:'ok', effects:{modelChanged:true}}
    //   thinking+参数 → 解析 on/off → runtime.changeThinking(...) → 同上
    //   412 冲突 → {kind:'error', output:'会话已被其它操作修改,请刷新后重试'}
    // 【CLIENT_LOCAL 分支(终审复查③)】/new 必须在 POST 之前本地处理:
    //   if (name === 'new') return { kind:'ok', output:'已开始新对话(本页视图已清空)',
    //                               effects:{ conversationReset:true } };
    //   ——零网络请求;无会话时同样直接返回(ok,视图本就是引导态),不发请求
    // 其余 SERVER 命令:POST /sessions/{id}/commands/{name}  body {arguments: args}
    // 成功后按 effects 逐键分发(归属见 3.6 表):
    //   modelChanged/thinkingChanged → 别名路径已由 changeModel 内部刷新 session
    //   conversationReset → 由 App.vue 处理(见 3.5)
  }

  return { commands, loaded, executing, load, matchCommand, execute };
}
```

**[终审复查②已采纳]** `load` 伪代码的 `GET /commands` 已改为必须带 `?all=true`(三态 matcher 数据源);测试清单同步:load 用例断言 query 参数含 `all=true`。


**[终审复查③已采纳]** execute 伪代码补 CLIENT_LOCAL 分支:`name === 'new'` 在 POST 之前本地返回 `{kind:'ok', effects:{conversationReset:true}}`(零网络请求,无会话时同样直接返回);测试断言无会话 `/new` 的产品决策与零网络请求。


**[复审③已采纳] 草稿清空条件收紧**:`runCommand()` 只在 `kind === 'ok'` 时清空 `message`;`kind === 'error'`(参数错/未知模型/412 冲突)**保留草稿**让用户改后重试;网络/HTTP 异常继续抛出走 catch(现有草稿保留语义)。对应 `runCommand` 伪代码中 `message.value = ''` 一行迁移到 `if (result.kind === 'ok')` 分支内;别名路径的 412 同样组装 `kind:'error'`(草稿保留)而非 ok。

**关键约定**:`matchCommand` 是**唯一**的拦截判定点,与后端 `isRegistered` 同规则(首个 token 匹配),保证前端拦截与后端守卫行为一致——不匹配的 `/xxx` 走普通消息,两边都不会误杀。

**[批注⑤已采纳] `matchCommand` 改为三态返回**。前端列表加载取**全量**(`GET /commands?all=true`,含 webCapable=false;菜单过滤在前端做):

```ts
type SlashMatch =
  | { type: 'executable'; command: SlashCommandDescriptor; arguments: string }   // webCapable=true
  | { type: 'web-reserved'; command: SlashCommandDescriptor; arguments: string } // 在 Catalog 但 webCapable=false
  | { type: 'unknown' };                                                          // 不在 Catalog,透传
```

- `executable` → 走命令执行路径(别名分流见 execute)
- `web-reserved` → **不透传**(后端守卫也会拒),本地系统消息"该命令仅在终端可用",零网络请求
- `unknown` → 普通消息(与后端守卫透传同源一致)

菜单只渲染 `executable` 项——不展示一个点不了的命令。


### 3.3 `CommandMenu.vue`(新建组件,约 150 行)

**Props / Emits**:

```ts
props: {
  visible: boolean;            // 由父组件控制显隐
  filter: string;              // 当前输入的命令前缀(如 '/mo')
  commands: SlashCommandDescriptor[]; // 已过滤前的全量
  activeIndex: number;         // 受控高亮项(键盘导航状态在父组件,便于统一处理)
};
emits: {
  select: [command: SlashCommandDescriptor];  // Enter/点击:确认选择
  close: [];                                   // Esc/失焦/清空前缀
};
```

**行为**:
- 过滤两步(终审复查④):先按 `webCapable === true` 过滤(App 传入的是 `?all=true` 全集,不过滤会把 TUI-only 项展示出来),再按 `filter` 前缀过滤(name 以去掉 `/` 的串开头),按 `category` 分组展示
- 每项两行:`/name argsHint` + description 灰字
- 无匹配时显示"没有匹配的命令"占位(**不**自动关闭——用户可能还在输入)
- 定位:`absolute` 悬浮于 Composer 上方(`bottom: 100%`),宽度与 composer 一致;`role="listbox"` + 项 `role="option"`,高亮项 `aria-selected`
- 点击项 = `emit('select')`(**仅补全**,与普通 Enter 同语义;见 3.4 两段式);纯展示组件,键盘事件由父组件统一处理(见 3.4)

**[终审复查④已采纳]** 组件行为补两步过滤(webCapable 先于前缀);点击/普通 Enter 统一为仅补全,只有 Cmd/Ctrl+Enter 补全并 emit `commandSubmit`——3.3 与 3.4 的 Enter 语义不再冲突。


### 3.4 `ComposerBox.vue` 改动(键盘与菜单集成)

新增 props/emit:

```ts
props 追加: slashCommands: SlashCommandDescriptor[];
emits 追加: commandSubmit: [payload: { name: string; arguments: string }];
```

`onKeydown` 改造(完整逻辑,替换现有实现):

```ts
function onKeydown(event: KeyboardEvent): void {
  // —— 命令菜单激活时的导航(优先级最高) ——
  if (menuActive.value) {
    if (event.key === 'ArrowDown') { event.preventDefault(); moveActive(1); return; }
    if (event.key === 'ArrowUp')   { event.preventDefault(); moveActive(-1); return; }
    // 注意顺序:带修饰键的 Enter 必须先于普通 Enter 判断,否则被普通分支吞掉
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); completeAndRun(); return; } // 直达
    if (event.key === 'Tab' || event.key === 'Enter') { event.preventDefault(); completeActive(); return; } // 两段式:只补全
    if (event.key === 'Escape')    { menuActive.value = false; return; }
    return; // 菜单激活时其它键(含普通输入)先放行到 textarea,由 updateText 重新过滤
  }
  // —— 原有 Enter 逻辑不变 ——
  if (event.key !== 'Enter') return;
  if (event.shiftKey && !(event.metaKey || event.ctrlKey)) return;
  event.preventDefault();
  if (event.shiftKey && (event.metaKey || event.ctrlKey) && props.running) {
    emit('submit', props.mode === 'steer' ? 'queue' : 'steer');
    return;
  }
  emit('submit');
}
```

`updateText` 追加菜单显隐逻辑:

```ts
function updateText(event: Event): void {
  const textarea = event.target as HTMLTextAreaElement;   // 再复审④:先取元素(与现有一致)
  textarea.style.height = 'auto';
  textarea.style.height = `${Math.min(textarea.scrollHeight, 220)}px`;   // 高度自适应保留
  emit('update:modelValue', textarea.value);

  // 复审④+再复审②:parser 同规范,菜单激活依据 hasSeparator(补全尾空格后正确收起)
  const parsed = parseSlashInput(textarea.value);
  menuActive.value = !props.running && parsed !== null && !parsed.hasSeparator;
}
```

**[复审④已采纳] 菜单显隐复用同一 parser**:`updateText` 不再手写 `startsWith('/') + 检查普通空格`,改为调用 `parseSlashInput(value)`(与后端共享规范的 TS 实现,1.3 锁定用例同源):

```ts
const parsed = parseSlashInput(value);
menuActive.value = !props.running && parsed !== null && !parsed.hasSeparator;
// 激活依据分隔符而非 arguments 是否为空(再复审②:补全写入 '/model ' 后
// hasSeparator=true,菜单正确收起,Enter 走 submit 分流);
// 过滤前缀 = parsed.name;前导空白容忍与后端一致
```


提交路径(终审复查④统一):菜单激活时**普通 Enter/Tab/点击 = 只补全**(`completeActive()`,不 emit);**仅 Cmd/Ctrl+Enter = 补全并 emit `commandSubmit`**(`completeAndRun()`)——与 3.4 两段式决议一致。

**[批注⑥已采纳] Enter/Tab 统一两段式,消除误触发**:

- 菜单激活时 **Tab 或 Enter = 只补全**(输入替换为 `/name ` 带尾空格,菜单收起,草稿保留)——不存在"Enter 直接执行"
- 补全后再按普通 **Enter 才执行**(此时无菜单,走 `submit()` 前置分流)——两段确认,高亮误触 `/new` 风险消除
- 空候选 / 列表加载失败 / `activeIndex` 无有效值 → Enter **回落普通 Composer 行为**(未匹配文本走 unknown 透传)
- 快捷直达:菜单激活时 **Cmd/Ctrl+Enter = 补全并立即执行**(效率入口;普通 Enter 永远安全)

`onKeydown` 菜单分支:Enter(无修饰)/Tab → `completeActive()`;Cmd/Ctrl+Enter → `completeAndRun()`;↑↓/Esc 不变。


### 3.5 `App.vue` 改动(命令执行接线)

```ts
const slash = useSlashCommands(runtime);
onMounted(() => void slash.load());   // 与 createSession 并行,失败静默

// ComposerBox 绑定
<ComposerBox
  ...现有 props...
  :slash-commands="slash.commands.value"
  @command-submit="runCommand"
/>

async function runCommand(payload: { name: string; arguments: string }): Promise<void> {
  if (submitting.value) return;
  submitting.value = true;
  try {
    const result = await slash.execute(payload.name, payload.arguments);
    appendSystemMessage(result.output, result.kind === 'error'); // 见下
    if (result.effects.conversationReset) {
      runtime.clearSessionView();   // 终审复查⑥:落实既有方法,不止注释
    }
    // 其余 effects 已在 useSlashCommands.execute 内分发(刷新会话/侧栏)
    if (result.kind === 'ok') message.value = '';   // 复审③:仅成功清空;error 保留草稿供修改重试
  } catch {
    // RuntimeApiError:显示 lastError(现有错误条);草稿保留
  } finally {
    submitting.value = false;
  }
}
```

**[终审复查⑥已采纳]** 批注属实:现有 `App.submit()` 首行 `if (!text || submitting.value || !runtime.hasSession.value) return;` 在三态分流之前拦截,无会话 `/new` 本地成功分支不可达。修订——**slash 分流前置于 session 守卫,仅 CLIENT_LOCAL 豁免 session 检查**:

```ts
async function submit(overrideMode?: FollowUpMode): Promise<void> {
  const draft = message.value;
  const text = draft.trim();
  if (!text || submitting.value) return;

  // 终审复查⑥:分流前置于 session 守卫;CLIENT_LOCAL(/new)豁免 session 检查
  if (!running.value) {
    const matched = slash.matchCommand(text);
    const isClientLocal = matched.type === 'executable'
        && matched.command.executionMode === 'CLIENT_LOCAL';
    if (matched.type === 'executable' && (runtime.hasSession.value || isClientLocal)) {
      await runCommand({ name: matched.command.name, arguments: matched.arguments });
      return;
    }
    if (matched.type === 'web-reserved') {
      appendSystemMessage(`/${matched.command.name} 仅在终端可用`, true);
      return;
    }
    // unknown → 继续(无会话时由下方原守卫拦截普通消息)
  }
  if (!runtime.hasSession.value) return;   // 原守卫保留,位于分流之后
  // ...原有发送/steer/queue 逻辑不变...
}
```

`/new` 的 effects 分支同步落实:`runtime.clearSessionView()`(useRuntimeApi 既有方法,deleteSession 同款,App.vue:83 已有调用先例)——不止注释;系统消息照常插入本地 systemMessages(引导态时间线为空时显示于顶部,刷新消失符合 D4)。

**[终审复查⑨已采纳]** 接线方案:`App.vue` 在 `!hasSession` 的 welcome 分支顶部插入 `<SystemNoticeStack :messages="systemMessages" />`(有/无会话两分支共用同一数据源;ConversationTimeline 不动,引导态由 Stack 承接渲染)。测试:`/new` 有会话(时间线清空 + Stack 提示)与无会话(Stack 提示)均有可见反馈。



(前置分流的完整实现见上方终审复查⑥修订的 `submit()`——分流位于 session 守卫之前且 CLIENT_LOCAL 豁免;此段历史版本保留对照。)

### 3.6 系统消息(`ConversationTimeline`)

- `App.vue` 本地维护 `systemMessages: ref<{ id, text, isError, at }[]>`(或并入现有 events 数组为特殊 kind,**不持久化**——刷新后消失是预期行为,命令效果已在会话状态里)
- 时间线渲染:区别于 user/assistant 的居中灰字样式(参考 accepted-list 的视觉层级),`isError` 时红色
- `runtimeEventProjector` **不改**——系统消息不经过 SSE 投影管线

**[批注⑦已采纳] effects 逐项标注状态归属**:

| effect | 归属 | 刷新后 | 说明 |
|---|---|---|---|
| `modelChanged` | **服务端持久化** | 保持 | 经 changeModel(PUT /model)真实写入 |
| `thinkingChanged` | **服务端持久化** | 保持 | 经 changeThinking(PUT /thinking)真实写入 |
| `conversationReset` | **客户端本地** | 回到服务端真实历史 | 仅 `clearSessionView()`,不结束服务端会话;系统消息附注"时间线已在本页清空,会话历史仍在服务端" |
| `sessionRenamed` | — | — | 首版已移出(/name 削减) |
| `historyCompacted` | — | — | 首版已移出(/compact 削减) |


### 3.7 前端测试(vitest)

- `useSlashCommands.test.ts`:
  - `load` 缓存(force 才重拉)/ 失败静默置空;**请求断言 query 含 all=true**(终审复查②)
  - `execute('/new')` → 本地返回 conversationReset、**零网络请求**(无会话时同样,终审复查③)
  - `matchCommand` 三态: `/model x`→executable、`/hotkeys x`→web-reserved、`/abc`→unknown、`/mo`(未完整名)→unknown、非 `/` 开头→unknown
  - `execute` 别名分流: model/thinking 带参走 changeModel/changeThinking(含 412);effects 未知键忽略
- `CommandMenu` 交互测试:webCapable 过滤(全集输入不出 TUI 项)、前缀过滤、分类分组、空态占位、select/close emit
- App 级(可选):命令提交后输入框清空 + 系统消息插入

**[批注⑧已采纳] 测试与配套同步清单**:

前端 vitest 增补:
- **三方一致性**:1.3 的 parseSlashInput 锁定用例在 Catalog(Java)/守卫(Java)/matcher(TS)各自跑出相同结果——共享用例 JSON,双端 CI 校验
- 过期 ETag:`/model glm-5` 别名触发 changeModel 412 → 提示刷新重试
- TUI-only 文本(`/hotkeys x`)→ web-reserved → 本地提示且**零网络请求**
- 空菜单 Enter / 列表加载失败 Enter → 回落普通行为;无会话执行 `/new` → 成功清理引导态 + 零网络请求(终审复查⑦:与 3.5 统一)
  - App.submit 层测试:session 守卫被 CLIENT_LOCAL 分流正确绕过(/new 无会话可达 runCommand;普通消息无会话仍被拦)
- a11y:textarea `aria-expanded`/`aria-controls`/`aria-activedescendant`;Esc 后焦点仍在 textarea

**[终审复查⑦已采纳]** 两处期望统一为"成功清理引导态、零网络请求";新增 App.submit 层测试覆盖守卫绕过。


后端增补:
- `WebCommandCatalogTest`:parseSlashInput 锁定用例全集 + isRegistered 边界
- `RuntimeErrorCode` 注册 `COMMAND_NOT_ROUTED`/`COMMAND_NOT_AVAILABLE_ON_WEB`,同步:中英 i18n 资源、前端 `friendlyError()` 映射、异常处理器 HTTP 状态绑定


---

## 第四部分:联调与验收

### 4.1 分工并行

- 后端先交付两个端点 + events 守卫(可用 curl 验收:`curl GET .../commands`、`curl POST .../commands/model -d '{"arguments":""}'`)
- 前端拿到契约即可 mock `useSlashCommands`(vitest mock fetch),组件开发不被后端阻塞
- 联调点:错误码映射(`COMMAND_NOT_ROUTED` → 前端"请使用命令菜单或直接输入完整命令"提示;再复审⑤已纠正本行原先的 ROURTED 拼写错误)、409 场景


### 4.2 手动验收清单

1. 输入 `/` 出菜单,`/mo` 过滤到 model,↑↓ 导航,**Enter/Tab 补全为 `/model `(不执行)**;再按 Enter → 系统消息显示当前模型(空参查询)
2. `/model glm-5` 提交 → 走 changeModel 别名(PUT /model + If-Match)→ 系统消息 + 模型名刷新;人为篡改 ETag 重放 → 412 提示刷新
3. `/thinking on` → 同上经 changeThinking
4. `/new` → 时间线清空 + 系统消息"仅清空当前视图,会话历史仍在服务端"
5. 输入 `/hotkeys` → 本地提示"该命令仅在终端可用",**Network 面板零请求**
6. curl 直接 `POST .../events` body `{"message":"/model glm-5"}` → 400 COMMAND_NOT_ROUTED;`/hotkeys` 同样 400
7. curl `POST .../commands/hotkeys` → 400 COMMAND_NOT_AVAILABLE_ON_WEB(非 404)
8. 输入 `/abc`(非命令)→ 作为普通消息发送给模型(守卫与前端同判定)
9. running 态输入 `/` → 无菜单,原 steer/queue 语义不受影响
10. Esc 关菜单后输入仍在、焦点在 textarea;空候选时 Enter 回落普通行为

## 边界情况

| 场景 | 行为 |
|---|---|
| 无会话时输入 `/` | 菜单照常;**CLIENT_LOCAL(/new)豁免可执行**(终审复查⑥),SERVER 命令提示先创建会话(不发请求) |
| 命令列表加载失败 | 静默降级:无菜单;`/model x` 等由后端守卫兜底 COMMAND_NOT_ROUTED(前端 unknown 透传后被拒,提示一致) |
| TUI-only 命令(`/hotkeys`) | 三态 web-reserved:本地提示,零网络请求 |
| 未知 effects 键 | 前端忽略(向前兼容) |
| executing 中重复提交 | submitting 状态复用,按钮禁用 |
| `/` 后立即空格 | 菜单收起(首个 token 结束);`/ ` 解析 name='' → unknown 透传 |
| 命令参数含敏感值 | 首版 5 命令均无敏感参数;/login 类不在 Catalog webCapable 集 |
| ETag 过期(别名路径) | 412 → 系统消息"会话已被修改,请刷新",自动 getSession 重取 ETag |

**[终审复查⑩已采纳]** 边界表"无会话"行改为 CLIENT_LOCAL 豁免/SERVER 拦截;resume/skill/Extension 的无会话策略统一表见 gap-analysis 文档 2.6。


## 设计决策(浓缩,理由见 v1)

- **D1 独立命令端点**:命令不是对话,不进历史/不触发 LLM/无 SSE;**已有配置端点的写操作走别名**不经命令端点(If-Match 完整性)
- **D2 服务端守卫为主**:前端拦截是体验,events 端点按 Catalog 全集守卫 → COMMAND_NOT_ROUTED;TUI-only 命令在命令端点 → COMMAND_NOT_AVAILABLE_ON_WEB
- **D3 webCapable 三态分级**:executable / web-reserved / unknown——菜单只展示 executable,reserved 本地提示零请求,unknown 双端一致透传
- **D4 结果为本地系统消息**:不持久化;effects 逐项标注**服务端持久化 / 客户端本地**归属
- **D5 首版 5 命令**:model/thinking/new/help/settings(compact/name/export 移出,条件成熟重开)
- **D6 单一目录源**:WebCommandCatalog 是菜单/守卫/执行/matcher 唯一数据源;parseSlashInput 规则写死,前后端共享锁定用例
- **D7 两段式确认**:Enter/Tab 只补全,普通 Enter 才执行;Cmd/Ctrl+Enter 为直达快捷键

## 性能(DFX)

- `GET /commands` 静态数据:前端内存缓存,会话期间不重拉;后端可加 ETag(首版不必)
- 命令执行同步 <100ms 量级,无 SSE 无轮询
- 守卫/匹配均 O(1) 注册表查找

## 测试汇总

| 层 | 文件 | 用例数(估) |
|---|---|---|
| Java | `WebCommandCatalogTest`(parseSlashInput 锁定用例 + isRegistered) | ~10 |
| Java | `RuntimeCommandControllerTest`(含 NOT_AVAILABLE_ON_WEB/412) | ~8 |
| Java | `RuntimeEventServiceTest` 增补(守卫全分支) | ~4 |
| Java | `WebCommandInvokerTest`(5 命令参数分支) | ~10 |
| TS | `useSlashCommands.test.ts`(三态/别名/ETag/一致性) | ~12 |
| TS | `CommandMenu` 测试(含 a11y) | ~6 |

## 验证命令

```bash
# 后端
./mvnw -pl modules/coding-agent-cli test
# 前端
cd frontend && npm test && npm run typecheck
```
