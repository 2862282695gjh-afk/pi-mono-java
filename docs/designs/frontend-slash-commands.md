# 前端 Slash Commands 功能设计文档

> 模块:`coding-agent-cli`(runtimeapi) + `frontend/`
> 状态:Proposed
> 日期:2026-08-25(详细版 v2)
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
      "webCapable": true
    },
    {
      "name": "hotkeys",
      "description": "TUI 快捷键列表",
      "argsHint": "",
      "category": "tui",
      "webCapable": false
    }
  ]
}
```

字段语义:
- `name`:命令名,**不含斜杠**;`^[a-z][a-z0-9-]*$`(与现有命令名一致)
- `argsHint`:参数提示给补全菜单展示(如 `[model-id]`),可为空串
- `category`:`session` | `conversation` | `system` | `tui`——前端菜单分组
- `webCapable`:false 时前端不展示(`?all=true` 查询参数供诊断面板用,首版可不做)

**默认只返回 `webCapable=true` 的命令**。无需会话上下文(命令集是进程级静态数据)。

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
    "output": "已切换模型: glm-5",
    "effects": { "modelChanged": true }
  }
}
```

`kind` 三值:
- `ok`:成功,`output` 为给用户看的反馈文本
- `error`:命令业务失败(如未知模型名),`output` 为安全摘要
- `no-session`:理论上不会出现(路径已含 sessionId),保留作防御

`effects` 已知键(前端逐个处理,未知键忽略——向前兼容):

| 键 | 类型 | 前端动作 |
|---|---|---|
| `modelChanged` | boolean | 刷新会话模型显示(等价 `changeModel` 成功后的本地刷新) |
| `thinkingChanged` | boolean | 刷新思考级别显示 |
| `sessionRenamed` | boolean | 刷新会话标题(侧栏) |
| `conversationReset` | boolean | 清空当前时间线(`/new` 用) |
| `historyCompacted` | boolean | 标记历史已压缩(首版可仅提示) |

**同步短请求,无 SSE**。HTTP 状态码:200 成功(kind=ok/error 都可能是 200);404 未知命令名;400 无效参数格式;409 会话 streaming 中执行了互斥命令;500 内部错误(信封 `message` 给安全摘要)。

### 1.3 `POST …/events` 行为变更

`RuntimeEventService.submit` 增加守卫:消息以 `/` 开头**且**去掉斜杠后的首个 token 匹配已注册命令名(不区分 webCapable)时,返回错误码 `COMMAND_NOT_ROUTED`(400),message 提示"命令请经 /commands 端点执行"。不匹配任何命令名的 `/xxx` 正常透传(与 TUI `execute() 返回 false` 语义一致)。

---

## 第二部分:后端实现(Java)

### 2.1 文件清单

```
modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/
├── command/
│   ├── WebCommandInvoker.java          ← 新增:执行入口
│   ├── WebCommandDefinition.java       ← 新增:命令定义 record
│   └── WebCommandEffect.java           ← 新增:effects 常量/record
├── dto/
│   ├── CommandDescriptorDTO.java       ← 新增
│   └── CommandResultDTO.java           ← 新增
└── web/
    └── RuntimeCommandController.java   ← 新增
修改:runtimeapi/event/RuntimeEventService.java(/守卫)
修改:runtimeapi/error 相关(注册 COMMAND_NOT_ROUTED 错误码)
```

### 2.2 `WebCommandDefinition`(命令注册表,不走 SlashCommandRegistry)

```java
public record WebCommandDefinition(
        String name,
        String description,
        String argsHint,
        String category,
        BiFunction<RuntimeSessionHolder, String, CommandOutcome> handler) {}
```

**为什么不复用 `SlashCommandRegistry`**:TUI 命令的 `SlashCommandContext` 携带 `AgentSession + OutputWriter`(终端语义),27 个命令大量直接操作 TUI 状态;Web 侧上下文是 `RuntimeSessionHolder`(runtime 会话)。硬桥接需要重写所有命令的上下文适配,不如为新语义建独立注册表,首版只注册 Web 能做的 8 个命令,handler 内部**复用底层会话操作方法**(如下)。

`WebCommandInvoker` 内置注册表(伪代码,首版 8 个):

| name | argsHint | handler 要点(都操作 `RuntimeSessionHolder` 现有能力) |
|---|---|---|
| `model` | `[model-id]` | 空参:读当前模型 id 返回;有参:调会话已有的模型变更路径(同 `RuntimeSessionConfigurationController` 的 model 端点逻辑),effects=modelChanged |
| `thinking` | `[on\|off]` | 同上,走 thinking 配置路径,effects=thinkingChanged |
| `name` | `[title]` | 重命名会话(若 runtime 已有 rename 能力;没有则本命令首版降级为只读显示当前标题),effects=sessionRenamed |
| `new` | — | 结束当前会话上下文:返回提示文本 + effects=conversationReset(**不物理删会话**,前端按 reset 清时间线并引导新建) |
| `compact` | — | 首版:返回"历史压缩已由服务自动执行"提示 + effects=historyCompacted(真实触发压缩若 runtime 未暴露,登记 DEFERRED) |
| `help` | — | 输出 webCapable 命令列表文本 |
| `settings` | — | 只读:当前模型/思考级别/会话标题摘要 |
| `export` | `[format]` | 首版仅 `text`:把会话历史拼为纯文本返回(前端弹下载);其它 format 返回 error |

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
    public ResultBean<List<CommandDescriptorDTO>> list() { ... }   // 静态注册表映射,无会话依赖

    @PostMapping("/sessions/{sessionId}/commands/{name}")
    public ResultBean<CommandResultDTO> execute(
            @PathVariable @NotBlank @Pattern(regexp = SESSION_ID_REGEX) String sessionId,
            @PathVariable @Pattern(regexp = "^[a-z][a-z0-9-]*$") String name,
            @RequestBody CommandExecuteRequestDTO body) {
        // 404: 未注册命令名(异常处理器映射)
        // 409: 会话 streaming 且命令标记互斥(model/thinking/name)
        // 其余: invoker.invoke(holder, name, body.arguments())
    }
}
```

### 2.4 `/events` 守卫(RuntimeEventService)

```java
// submit() 入口、校验 message 之后:
String head = firstToken(message);  // 去掉 '/' 的第一个空白分隔 token
if (message.startsWith("/") && commandInvoker.isRegistered(head)) {
    throw new RuntimeApiException(RuntimeErrorCode.COMMAND_NOT_ROUTED, head);
}
```

`COMMAND_NOT_ROUTED` 加入 `RuntimeErrorCode`,HTTP 映射 400。

### 2.5 后端测试(清单)

- `RuntimeCommandControllerTest`(MockMvc 或现有测试风格):
  - `GET /commands` 只含 webCapable 命令,字段齐全
  - `POST /commands/model`(空参)→ 200 + kind=ok + 当前模型 id
  - `POST /commands/model`(有效参)→ effects.modelChanged=true
  - `POST /commands/unknown` → 404
  - `POST /commands/model` 于 streaming 会话 → 409
  - 无效 name 路径段(`/commands/Model`)→ 400
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

### 3.2 `useSlashCommands.ts`(新建 composable,约 120 行)

```ts
export function useSlashCommands(runtime: ReturnType<typeof useRuntimeApi>) {
  const commands = ref<SlashCommandDescriptor[]>([]);
  const loaded = ref(false);
  const executing = ref(false);

  async function load(force = false): Promise<void> {
    if (loaded.value && !force) return;
    // GET /commands,走 useRuntimeApi 同源的 requestRaw/错误处理
    // 失败静默降级:commands 保持空,输入 '/' 无菜单(不阻塞主流程)
  }

  /** 输入文本是否应拦截为命令(仅非 running 态拦截;running 态 steer/queue 语义不变) */
  function matchCommand(text: string): { command: SlashCommandDescriptor; arguments: string } | null {
    // 仅当 text 以 '/' 开头且首个 token === 某命令 name 时返回;否则 null
    // 例: '/model glm-5' → { command: model 命令, arguments: 'glm-5' }
    // '/abc' → null(透传为普通消息)
  }

  /** 执行命令并分发 effects;返回结果供调用方插入系统消息 */
  async function execute(name: string, args: string): Promise<SlashCommandResult> {
    // POST /sessions/{id}/commands/{name}  body {arguments: args}
    // 成功后按 effects 逐键分发:
    //   modelChanged/thinkingChanged → runtime 刷新会话(复用现有 getSession)
    //   sessionRenamed → 刷新侧栏线程列表
    //   conversationReset → 由 App.vue 处理(见 3.4)
  }

  return { commands, loaded, executing, load, matchCommand, execute };
}
```

**关键约定**:`matchCommand` 是**唯一**的拦截判定点,与后端 `isRegistered` 同规则(首个 token 匹配),保证前端拦截与后端守卫行为一致——不匹配的 `/xxx` 走普通消息,两边都不会误杀。

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
- 按 `filter` 前缀过滤 `commands`(name 以去掉 `/` 的串开头),按 `category` 分组展示
- 每项两行:`/name argsHint` + description 灰字
- 无匹配时显示"没有匹配的命令"占位(**不**自动关闭——用户可能还在输入)
- 定位:`absolute` 悬浮于 Composer 上方(`bottom: 100%`),宽度与 composer 一致;`role="listbox"` + 项 `role="option"`,高亮项 `aria-selected`
- 点击项 = `emit('select')`;纯展示组件,键盘事件由父组件统一处理(见 3.4)

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
    if (event.key === 'Tab')       { event.preventDefault(); completeActive(); return; } // Tab:补全命令名(留个尾空格)
    if (event.key === 'Enter')     { event.preventDefault(); chooseActive(); return; }   // Enter:选中并提交
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
  // ...现有高度自适应逻辑不变...
  const value = textarea.value;
  // '/' 触发:光标前文本是单独的首个 '/'(即正在输入第一个 token)且非 running 态
  menuActive.value = !props.running
      && value.startsWith('/')
      && !value.slice(1).includes(' ');   // 参数出现后菜单收起(命令名已确定)
}
```

提交路径:菜单激活时 Enter 走 `chooseActive()`(emit `commandSubmit`),不走原 `submit`。

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
      // /new:清时间线 + 引导态(复用现有"首次进入"分支的状态设置)
    }
    // 其余 effects 已在 useSlashCommands.execute 内分发(刷新会话/侧栏)
    message.value = '';   // 命令成功后清空输入(与消息提交一致)
  } catch {
    // RuntimeApiError:显示 lastError(现有错误条);草稿保留
  } finally {
    submitting.value = false;
  }
}
```

原 `submit()` **加一行前置分流**(双保险,防止菜单关闭状态下的漏网 `/cmd`):

```ts
const matched = slash.matchCommand(text);
if (!running.value && matched) { await runCommand({ name: matched.command.name, arguments: matched.arguments }); return; }
```

### 3.6 系统消息(`ConversationTimeline`)

- `App.vue` 本地维护 `systemMessages: ref<{ id, text, isError, at }[]>`(或并入现有 events 数组为特殊 kind,**不持久化**——刷新后消失是预期行为,命令效果已在会话状态里)
- 时间线渲染:区别于 user/assistant 的居中灰字样式(参考 accepted-list 的视觉层级),`isError` 时红色
- `runtimeEventProjector` **不改**——系统消息不经过 SSE 投影管线

### 3.7 前端测试(vitest)

- `useSlashCommands.test.ts`:
  - `load` 缓存(force 才重拉)/ 失败静默置空
  - `matchCommand`: `/model x` 命中、`/mo` 前缀不命中(未完整)、`/abc` null、非 `/` 开头 null
  - `execute` effects 分发:modelChanged 触发 getSession、未知 effects 键忽略
- `CommandMenu` 交互测试:前缀过滤、分类分组、空态占位、select/close emit
- App 级(可选):命令提交后输入框清空 + 系统消息插入

---

## 第四部分:联调与验收

### 4.1 分工并行

- 后端先交付两个端点 + events 守卫(可用 curl 验收:`curl GET .../commands`、`curl POST .../commands/model -d '{"arguments":""}'`)
- 前端拿到契约即可 mock `useSlashCommands`(vitest mock fetch),组件开发不被后端阻塞
- 联调点:错误码映射(COMMAND_NOT_ROURTED → 前端"请使用命令菜单或直接输入完整命令"提示)、409 场景

### 4.2 手动验收清单

1. 输入 `/` 出菜单,`/mo` 过滤到 model,↑↓ 导航,Tab 补全为 `/model `
2. Enter 提交 `/model`(空参)→ 系统消息显示当前模型
3. `/model glm-5` → 系统消息 + 侧栏/头部模型名刷新(modelChanged)
4. `/new` → 时间线清空回到引导态
5. curl 直接 `POST .../events` body `{"message":"/model glm-5"}` → 400 COMMAND_NOT_ROUTED
6. 输入 `/abc`(非命令)→ 作为普通消息发送给模型
7. running 态输入 `/` → 无菜单,原 steer/queue 语义不受影响
8. Esc 关菜单后输入仍在,可继续编辑

## 边界情况

| 场景 | 行为 |
|---|---|
| 无会话时输入 `/` | 菜单照常;执行时后端 400(路径无有效 session)→ 前端错误条提示先创建会话 |
| 命令列表加载失败 | 静默降级:无菜单,`/cmd` 由后端守卫兜底报 COMMAND_NOT_ROUTED |
| 未知 effects 键 | 前端忽略(向前兼容) |
| executing 中重复提交 | submitting 状态复用,按钮禁用 |
| `/` 后立即空格 | 菜单收起(首个 token 结束),文本按普通消息发送 |
| 命令参数含敏感值 | 首版 8 命令均无敏感参数;/login 类不在 webCapable 集 |

## 设计决策(浓缩,理由见 v1)

- **D1 独立命令端点**:命令不是对话,不进历史/不触发 LLM/无 SSE
- **D2 服务端守卫为主**:前端拦截是体验,events 端点 `/`+已注册命令名 → COMMAND_NOT_ROUTED
- **D3 webCapable 分级**:TUI 专属命令不出现在 Web,服务端声明
- **D4 结果为本地系统消息**:不持久化,`effects` 声明式驱动既有刷新
- **D5 首版 8 命令**:model/thinking/name/new/compact/help/settings/export
- **D6 前后端同规则拦截**:matchCommand 与 isRegistered 都是"首个 token 精确匹配",永不误杀 `/xxx` 普通文本

## 性能(DFX)

- `GET /commands` 静态数据:前端内存缓存,会话期间不重拉;后端可加 ETag(首版不必)
- 命令执行同步 <100ms 量级,无 SSE 无轮询
- 守卫/匹配均 O(1) 注册表查找

## 测试汇总

| 层 | 文件 | 用例数(估) |
|---|---|---|
| Java | `RuntimeCommandControllerTest` | 6 |
| Java | `RuntimeEventServiceTest` 增补 | 2 |
| Java | `WebCommandInvokerTest`(每命令参数分支) | ~12 |
| TS | `useSlashCommands.test.ts` | ~8 |
| TS | `CommandMenu` 测试 | ~5 |

## 验证命令

```bash
# 后端
./mvnw -pl modules/coding-agent-cli test
# 前端
cd frontend && npm test && npm run typecheck
```
