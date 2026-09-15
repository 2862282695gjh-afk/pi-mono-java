# CampusClaw Frontend

v0.16.0，Vue 3 + TypeScript + Vite 的内部 Claw Runtime 联调工作台。
保留 O1 暖灰界面，提供 Session Events、Slash、工具确认、计划审核与恢复。仅用于受控开发环境，
不作为生产 Mate 产品前端或安全边界。

## 已实现与联调边界

- Session 创建/恢复/删除，刷新和重开恢复置顶/最近列表及选中会话；Session 与 ETag 一起应用。
- Sidebar 支持本地会话搜索、直接重命名和置顶。重命名复用统一 Session POST 与 If-Match，运行中只改名称；412 刷新资源并保留名称草稿，不自动重放。
- 五类 user.* event 包装、POST data-only SSE、eventId 合并、数字历史分页。
- 工具允许/拒绝，绑定原消息的停止；confirming 仍占用执行状态。
- Composer 工具栏常驻普通/计划模式菜单；模式、模型和思考通过统一 Session POST 更新并携带 ETag，900px 以下收纳模型与思考设置。
- 完整计划卡、原始 Markdown 复制、批准并执行、修改意见；复制失败可见且不改变审核状态，草稿按 Session 和计划事件隔离。
- 问题卡可手动折叠；已回答后显示摘要并可展开权威答案。校验聚焦首个错误，提交与接受状态管理焦点和单次播报，客户端 DOM 身份不使用协议 question id。
- 仅消费 `agent.todo_updated` 更新实施任务完整清单；新根消息重置，终态保留最终快照。任务清单和命令结果可分别折叠，Slash 清单打开时临时收起并在关闭后恢复选择。
- 完整事件覆盖预览；无回执保留草稿并明确结果未知，断流只重读、不重发。
- 空消息首字符 `/` 打开精简清单；名称不带前导斜杠，选中后显示可更换、删除的标签。
- 参数单独编辑；空闲时上箭头提交，消息提交及执行中变为停止按钮。运行中命令在独立“执行命令”按钮执行；第一次 Enter 只选择，长按 Enter 不执行。
- 首次 Escape 收起清单，再次退出；更换时 Escape 恢复原命令和参数，取消或成功后恢复普通消息草稿。
- Catalog 变化时保留参数并重新校验；Builtin 临时卡与现有内部 Runtime Skill SSE 分离。
- 原始推理与工具结果纯文本；Assistant 安全 Markdown、整轮复制和原暖灰活动框。
- DEV Headers 预置 `access-token`、`X-HW-ID`、`Authorization`，Value 留空且空预置不发送。
- 请求头可编辑、删除；“增加请求头”每次只新增一行，删除或全部清空允许零行，不自动补空行。
- DEV Headers 每次消息/命令/停止/确认 POST 各自快照，仅内存、不进入历史读取。

工作台消费 `/campusclaw-service/v1` 的 Session Events，服务端已接入联合事件、data-only SSE 和公共历史。
计划正文使用安全 Markdown 渲染，失败的计划工具调用仍可见。
Thinking 展示模型返回的原始推理；业务参数错误的工具调用保留为可读事件，结果与确认按所属根消息关联。
真实联调按下述同源代理方式进行，本地 fixture 通过不能替代真实服务验收。
运行中不再有 Steer/FollowUp/旧 Abort 入口；先实际停止，再提交新消息。

“+”打开附件/上下文入口，不再提供命令。上传接口未接入，菜单中的附件项禁用；有序 content 构造及纯附件/最多 4 个 fileId 有单元测试，
不代表实际上传已可用。公共 Mate Chat、附件服务和用户 Agent 中心不在 Claw 前端实现范围内；Agent 中心保留禁用原因，不接入管理接口或假数据。

## 启动真实联调

```bash
cd frontend
npm ci
VITE_BACKEND_URL=http://localhost:8080 npm run dev
```

浏览器打开 `http://localhost:5173`；`/campusclaw-service` 被代理到后端。
复制 `.env.example` 为 `.env.local` 并填写本地配置；修改后重启 Vite：

| 变量 | 用途 |
|---|---|
| `VITE_CAMPUSCLAW_AGENT_ID` | 默认内部 Agent ID；也可在 DEV 诊断入口手动输入 |
| `VITE_CAMPUSCLAW_AGENT_NAME/DESCRIPTION/CATEGORY` | 展示元数据 |
| `VITE_CAMPUSCLAW_API_BASE` | 默认留空同源；内部跨源需服务端 CORS 放行 ETag |
| `VITE_BACKEND_URL` | Vite 开发代理目标，默认 localhost:8080 |
| `VITE_CAMPUSCLAW_CALLER_ID` | 非秘密调用方标识，默认 campusclaw-web |
| `CAMPUSCLAW_DEV_AUTH_ACCOUNT` | 认证中心账号，仅本地 Node 开发服务读取 |
| `CAMPUSCLAW_DEV_AUTH_PASSWORD` | 认证中心密码，仅本地 Node 开发服务读取 |

不要把 JWT、APPKEY、Provider 凭据或 access-token 写入 `VITE_*`，它们会进入浏览器产物。
DEV 的 Headers 值仅在当前页面；GET/配置不携带临时凭据。协议 Accept/Content-Type 固定，
If-Match/Idempotency-Key/Last-Event-ID 不进入 Events/Command POST。Session 配置 POST 独立使用资源 ETag。
当前工作台直接显示后端返回的调用参数，部署及截图流转必须受控。

## 自动获取请求头 Token

本地配置账号、密码后，在 Headers 中填写认证中心 IP，再点击“获取 Token”。开发服务固定访问
该 IP 的 HTTPS 443 `/service/AuthCenter/0.1.0/auth/login`，读取独立 `Set-Cookie` 中的
`access-token`，回填并启用对应请求头；不会转发 JSESSIONID 或把 Cookie 写入浏览器。
地址不接受协议、端口、域名或路径。请求不自动发起或重试，失败保留手工值；编辑/清空请求头、
修改 IP 或离开页面后，迟到响应不会覆盖当前输入。修改 IP 时仅清除仍未被手工修改的自动 Token。

认证助手只在 `vite dev` 注册，要求本机回环连接、匹配的 Host 与同源 Origin；默认绑定 127.0.0.1。
保留 TLS 校验；企业 CA 可通过 Node 的 `NODE_EXTRA_CA_CERTS` 指向受信任 PEM 配置，不关闭证书验证。
本地凭据不得使用 `VITE_` 前缀；IP、Token 和所有请求头仅保存在当前页面内存。
生产构建与 `vite preview` 不提供此认证接口。真实认证中心登录仍需在用户本地配置后联调。

## 会话恢复

浏览器 localStorage 的 `campusclaw.session-index.v2:` 名称空间只保存会话标识、`lastActivityAt`、置顶状态、
当前选择和未确认请求的身份标记，按 API base、配置 Agent、caller 区分。v1 首次读取时原序迁移；选择、恢复、
重命名和配置只原位补丁，只有新建或消息收到确认回执后更新活动排序。消息正文及模型上下文
重新从后端历史读取；草稿、命令参数、Headers 和凭据均不落盘。清除浏览器数据会丢失恢复入口。
这份索引不是前端源码配置文件，也不是后端会话内容的备份。

恢复失败保留列表并提供重试；403/404 可从列表移除。恢复不会自动创建会话或重放 POST。
运行中断流后进行最多六轮只读状态核对（1、2、5、5、5、5 秒间隔），隐藏页面或切换会话取消，
耗尽后显示“刷新状态”；确认等待仍属于运行中，只有真实终态解除停止状态。

## 无后端的浏览器契约验收

两个终端分别运行；仅使用显式模拟值，无真实数据、上传或工具执行：

```bash
node scripts/fixture-runtime.mjs
```

```bash
VITE_BACKEND_URL=http://127.0.0.1:4319 VITE_CAMPUSCLAW_AGENT_ID=agent-fixture VITE_CAMPUSCLAW_AGENT_NAME='园区运维助手（契约模拟）' npm run dev -- --host 127.0.0.1 --port 5177 --strictPort
```

打开 `http://127.0.0.1:5177`。输入消息可验收确认等待→允许/拒绝→续跑，也可停止。
输入 / 验收清单与命令结果。fixture 不代表服务端校验、授权、并发或持久化实现。
Vite 构建不引用 fixture 脚本；没有生产 Mock fallback。

## 验证与设计

[计划模式实施说明](../docs/plans/campusclaw-plan-mode-implementation.md)记录源码接线与验证边界。
浏览器契约 fixture 与组件测试只证明前端行为；真实 Runtime/JAR、数据库及企业 Mate 联调分别记录。

```bash
npm test
npm run typecheck
npm run build
npm audit --audit-level=high
```

[实现设计及源码基线](../docs/designs/frontend-events/README.md) ·
[ADR-0102](../docs/decisions/0102-runtime-events-v2-frontend.html) ·
[工作台恢复与认证 ADR-0105](../docs/decisions/0105-workbench-session-stop-and-auth.html) ·
[历史视觉设计](../docs/designs/campusclaw-frontend.md)
