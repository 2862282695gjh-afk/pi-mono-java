# 前端 Slash Commands 功能设计文档

> 模块:`coding-agent-cli`(runtimeapi) + `frontend/`
> 状态:Proposed
> 日期:2026-08-25
> 契约基线:Runtime HTTP 1.38(`RuntimeApiConstants.BASE_PATH = /campusclaw-service/v1`)

---

## Context(为什么)

TUI 交互模式已具备完整的斜杠命令体系(`SlashCommandRegistry` + 27 个内置命令:模型切换/会话管理/导出/压缩等),由 `InteractiveMode:621` 在输入以 `/` 开头时拦截分发。但 Web 前端(CampusClaw 产品工作台,Vue 3)走的是 Runtime HTTP v1 链路:

```
ComposerBox → useRuntimeApi.sendMessage(message)
  → POST /sessions/{sessionId}/events  body: {message}
    → RuntimeEventController.submit → RuntimeEventService → agent.prompt(message)
```

链路中**无任何命令拦截**:用户输入 `/model glm-5` 会作为普通聊天文本发给模型;前端也无命令列表数据源与补全 UI。本设计将 TUI 的命令能力以产品化方式延伸到 Web 前端。

**约束**:
- Runtime HTTP 契约(`docs/plans/campusclaw-http-v1-implementation.md`)由 owner 维护,新增端点需与其对齐契约版本(本设计标注为 1.39 候选)。
- 前端不接收/显示内部 ID、ETag、原始 frame(README 既定安全边界);命令结果以产品消息呈现。
- `SlashCommandContext` 现仅含 `AgentSession + OutputWriter`(TUI 语义),Web 侧命令不能直接复用该上下文执行。

## 关键定义

| 名称 | 类型 | 位置 | 说明 |
|---|---|---|---|
| `CommandDescriptor` | DTO | `runtimeapi/dto` | 命令元数据:name/description/argsHint/category/webCapable |
| `CommandResultDTO` | DTO | `runtimeapi/dto` | 命令执行结果:output(文本)/kind(ok\|error\|no-session)/_effects(会话变更信号) |
| `RuntimeCommandController` | REST | `runtimeapi/web` | `GET …/commands` 列表 + `POST …/commands/{name}` 执行 |
| `CommandInvoker` | 接口 | `runtimeapi/event` | Web 侧命令执行抽象(替代 TUI 的 SlashCommandContext) |
| `CommandMenu.vue` | 组件 | `frontend/src/components` | `/` 触发的命令补全浮层 |
| `useSlashCommands` | composable | `frontend/src/composables` | 命令列表缓存 + 执行 + 结果投递 |

## 架构与数据流

```
[启动] 前端 → GET /campusclaw-service/v1/commands
        ← [{name:"model", argsHint:"[model-id]", category:"session", webCapable:true}, …]

[输入] ComposerBox 检测首个字符为 "/"
        → 打开 CommandMenu(前缀过滤 + ↑↓ 导航 + Tab/Enter 补全)
        → Enter 提交(带参数或先补全命令名)

[执行] POST /campusclaw-service/v1/sessions/{sessionId}/commands/{name}
        body: {arguments: "glm-5"}
        ← CommandResultDTO {kind:"ok", output:"Switched to model: glm-5",
                            _effects:{modelChanged:true}}
        → 前端按 _effects 刷新会话状态(changeModel 已有本地实现则直接复用),
          output 作为系统消息插入对话流(不入 agent 历史)

[拦截] sendMessage 前置守卫:消息以 "/" 开头时不走 events 端点,
        改走命令端点——与服务端双保险(前端拦截是体验,服务端拦截是正确性)
```

服务端执行路径:

```
RuntimeCommandController.execute(sessionId, name, body)
  → CommandInvoker.invoke(session, command, args)      // Web 语义上下文
      ├─ 会话类:直接操作 runtime session(模型/思考级别/会话配置)
      ├─ 查询类:读 session 状态生成文本
      └─ 不适配 Web 的命令(TUI 专属,如 /hotkeys):commands 列表里
         webCapable=false,前端置灰不展示或标注 "CLI only"
  → CommandResultDTO(无 SSE:命令是同步短操作)
```

## 设计决策

### D1. 命令经独立端点,不混入 events 消息流

**决策**:命令走 `POST /sessions/{id}/commands/{name}`,不作为 `message` 发进 events。

**理由**:命令不是对话内容——不该进 agent 历史、不该触发 LLM、不需要 SSE 流。混入消息流需要模型侧配合识别 `/` 前缀并回吐结果,污染上下文且不可靠。独立端点让命令保持同步、确定语义。

### D2. 服务端拦截为主,前端拦截为体验层

**决策**:即使前端 UI 已拦截 `/` 输入,`RuntimeEventService.submit` 仍增加服务端守卫:以 `/` 开头且匹配已注册命令名的消息直接返回错误提示(或内部转交命令执行),不发给模型。

**理由**:前端可被绕过(直接 curl events 端点);TUI 的拦截也只在 InteractiveMode,HTTP 层从未有过守卫。正确性必须在服务端闭环,前端拦截只为避免用户看到自己的命令被当聊天发出去。

### D3. 命令能力分级:webCapable

**决策**:`CommandDescriptor.webCapable` 标注命令是否可在 Web 执行;`GET /commands` 默认只返回 webCapable=true 的命令(查询参数 `?all=true` 供诊断)。

**理由**:27 个内置命令里相当一部分是 TUI 专属(`/hotkeys` 键盘快捷键、`/tree` 终端树渲染、`/debug` TUI 面板)。与其在前端硬编码过滤名单,不如服务端声明能力,前端零知识展示。

### D4. 命令结果作为系统消息,不进 agent 历史

**决策**:`CommandResultDTO.output` 由前端作为本地系统消息插入时间线(样式区别于 user/assistant),不写入会话持久化历史。

**理由**:命令副作用(如切模型)已通过 `_effects` 反映在会话状态;文本结果只是操作反馈,进入 agent 历史会污染后续模型上下文。`_effects` 采用声明式信号(如 `modelChanged`/`sessionRenamed`),前端据此调用既有刷新逻辑,避免结果文本解析。

### D5. 首版命令范围:会话与查询类

**决策**:首版 webCapable 集合限定:`/model`、`/thinking`(经既有 session config)、`/name`、`/new`、`/compact`、`/help`、`/settings`(只读展示)、`/export`。排除:登录类(前端有独立流程)、TUI 专属类、`/cron`(涉及凭据展示策略,二期评估)。

**理由**:与前端已有能力对齐(模型/思考切换前端已有 UI 与本地逻辑,命令走同一状态刷新路径),首版即可验证全链路;高风险命令(涉及凭据/系统操作)延后。

## 边界情况

| 场景 | 行为 |
|---|---|
| 无会话时执行命令 | 400 + `kind:"no-session"`;前端引导先创建会话 |
| 未知命令名 POST | 404;前端菜单只列已注册命令,直接 POST 属绕过 |
| 命令执行抛异常 | 500 → `kind:"error"` + output 为安全摘要(不泄内部堆栈) |
| 执行中(streaming)执行会话类命令 | 409 冲突提示,与现有 session config 端点行为一致 |
| `/` 开头但不匹配任何命令 | 走普通消息(与 TUI `execute() 返回 false` 语义一致) |
| 命令带敏感信息(如 /login 参数) | webCapable=false,根本不出现在列表 |

## 性能(DFX)

- `GET /commands` 结果可缓存(命令集随版本不变),前端内存缓存 + ETag
- 命令执行均为同步短操作(<100ms 量级),无 SSE、无阻塞
- 服务端守卫仅一次字符串前缀 + 注册表查找,O(1)

## 契约改动(Runtime HTTP 1.39 候选)

| 端点 | 说明 |
|---|---|
| `GET /campusclaw-service/v1/commands` | 命令描述列表(webCapable 过滤) |
| `POST /campusclaw-service/v1/sessions/{sessionId}/commands/{name}` | 执行命令,body `{arguments: string}` |
| `POST …/events` 行为变更 | `/` 前缀且匹配命令名的 message 被拒绝(错误码 `COMMAND_NOT_ROUTED`),提示走命令端点 |

## 测试

| 层 | 覆盖 |
|---|---|
| `RuntimeCommandControllerTest` | 列表过滤 webCapable / 执行 ok / 未知命令 404 / 无会话 400 / streaming 冲突 409 |
| `RuntimeEventServiceTest` 补充 | `/model x` 走 events 被拦截返回 COMMAND_NOT_ROUTED;`/非命令` 正常透传 |
| `CommandInvoker` 实现 | 每个首版命令的参数分支(空参/合法/非法) |
| 前端 `useSlashCommands.test.ts` | 列表缓存 / 前缀过滤 / 执行后 _effects 触发既有刷新 / 结果插入系统消息 |
| `CommandMenu` 交互 | `/` 触发 / ↑↓ 导航 / Tab 补全 / Esc 关闭 / 参数透传 |

## 验证

- `./mvnw -pl modules/coding-agent-cli test` + `cd frontend && npm test`
- 手动:浏览器输入 `/` 出菜单 → `/model` 空参显示当前模型 → `/model glm-5` 切换且侧栏模型名刷新 → curl 直接 POST events 带 `/model` 被拒
