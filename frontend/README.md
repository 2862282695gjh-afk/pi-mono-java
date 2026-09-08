# CampusClaw Frontend

v0.13.0，Vue 3 + TypeScript + Vite 的内部 Claw Runtime 联调工作台。
保留 O1 暖灰界面，新增 Events v2、Slash、工具确认与恢复。仅用于受控开发环境，
不作为生产 Mate 产品前端或安全边界。

## 已实现与联调边界

- Session 创建/恢复/删除，模型与后续思考摘要设置；Session 与 ETag 一起应用。
- 三类 user.* event 包装、POST data-only SSE、eventId 合并、数字历史分页。
- 工具允许/拒绝，绑定原消息的停止；confirming 仍占用执行状态。
- 完整事件覆盖预览；无回执保留草稿并明确结果未知，断流只重读、不重发。
- Slash 独立草稿、稳定前缀过滤、二次 Enter 执行；Builtin 临时卡与 Skill SSE 分离。
- 公开摘要与工具结果纯文本；Assistant 安全 Markdown、整轮复制和原暖灰活动框。
- DEV Headers 每次消息/命令/停止/确认 POST 各自快照，仅内存、不进入历史读取。

**必须使用已完成 v2 HTTP 接线的后端。** 源码同步基线 `9b01a9f1` 的新 VO 和底层服务已存在，
`RuntimeEventController` 仍接旧消息请求；本任务不修改它。新前端不回退旧 wire。
后端准备完成后，再按下述同源代理方式真实联调。不能用本地 fixture 通过替代真实服务验收。
运行中不再有 Steer/FollowUp/旧 Abort 入口；先实际停止，再提交新消息。

上传接口未接入，附件按钮禁用；有序 content 构造及纯附件/最多 4 个 fileId 有单元测试，
不代表实际上传已可用。公共 Mate Chat 不在本次范围内。

## 启动真实联调

```bash
cd frontend
npm ci
VITE_BACKEND_URL=http://localhost:8080 npm run dev
```

浏览器打开 `http://localhost:5173`；`/campusclaw-service` 被代理到后端。
`.env.example` 中的非秘密参数可复制到 `.env.local`：

| 变量 | 用途 |
|---|---|
| `VITE_CAMPUSCLAW_AGENT_ID` | 默认内部 Agent ID；也可在 DEV 诊断入口手动输入 |
| `VITE_CAMPUSCLAW_AGENT_NAME/DESCRIPTION/CATEGORY` | 展示元数据 |
| `VITE_CAMPUSCLAW_API_BASE` | 默认留空同源；内部跨源需服务端 CORS 放行 ETag |
| `VITE_BACKEND_URL` | Vite 开发代理目标，默认 localhost:8080 |
| `VITE_CAMPUSCLAW_CALLER_ID` | 非秘密调用方标识，默认 campusclaw-web |

不要把 JWT、APPKEY、Provider 凭据或 access-token 写入 `VITE_*`，它们会进入浏览器产物。
DEV 的 Headers 值仅在当前页面；GET/配置不携带临时凭据。协议 Accept/Content-Type 固定，
If-Match/Idempotency-Key/Last-Event-ID 不进入 Events/Command POST。配置 PUT 独立使用资源 ETag。
当前工作台直接显示后端返回的调用参数，部署及截图流转必须受控。

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

```bash
npm test
npm run typecheck
npm run build
npm audit --audit-level=high
```

[实现设计及源码基线](../docs/designs/frontend-events-v2/README.md) ·
[ADR-0102](../docs/decisions/0102-runtime-events-v2-frontend.html) ·
[历史视觉设计](../docs/designs/campusclaw-frontend.md)
