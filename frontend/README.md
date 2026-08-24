# CampusClaw Frontend

Vue 3 + TypeScript + Vite 实现的 CampusClaw Agent 工作区。界面采用 Codex-inspired 黑白
中性体系，以 Agent、会话、对话、工具活动和执行控制为产品概念，不再把 HTTP/SSE 调试字段
作为主界面。

当前版本按 CampusClaw Runtime HTTP 1.38.0 实现过渡 adapter。生产架构仍应通过
mate-service 公共 Agent/Chat/Attachment API；在该契约完成前，本 adapter 用于前后端并行
开发和内部环境验证，不代表生产安全边界。

## 已实现

- 首次进入、常规对话和执行中三个界面状态；
- Session 创建、恢复、删除、模型选择和深度思考；
- `POST /sessions/{sessionId}/events` 请求级 SSE 增量解析；
- 当前分支持久化历史分页、去重和流结束后的恢复；
- User、Assistant、Thinking 和工具生命周期的产品对象投影；
- 运行中“调整方向”“加入队列”“停止”；
- desktop 默认 `steer`，`Cmd/Ctrl+Shift+Enter` 对单条消息反转模式；
- Codex-inspired Design Token、键盘焦点、reduced-motion 和响应式侧栏。

界面不会接收或显示 JWT、APPKEY、ETag、内部 Session ID、原始 JSON 或 SSE frame。开发构建
保留一个可折叠诊断入口，仅允许临时指定 Agent ID 或恢复 Session；生产构建不会渲染该入口。

## 配置

复制 `.env.example` 为 `.env.local`，配置非秘密浏览器参数：

```bash
cp .env.example .env.local
```

| 变量 | 必需 | 说明 |
|---|---|---|
| `VITE_CAMPUSCLAW_AGENT_ID` | 当前过渡集成必需 | 默认 Agent 的内部 ID；公共 Agent 目录完成后移除 |
| `VITE_CAMPUSCLAW_AGENT_NAME` | 否 | 产品显示名称 |
| `VITE_CAMPUSCLAW_AGENT_DESCRIPTION` | 否 | 首次进入页描述 |
| `VITE_CAMPUSCLAW_AGENT_CATEGORY` | 否 | 首次进入页分类 |
| `VITE_CAMPUSCLAW_API_BASE` | 否 | 留空使用同源；仅内部环境需要跨源基址 |
| `VITE_CAMPUSCLAW_CALLER_ID` | 否 | 非秘密调用方标识，默认 `campusclaw-web` |

不要把 Provider 凭据、JWT、APPKEY 或其他秘密写入 `VITE_*`；Vite 会把这些值编译进浏览器
产物。

## 启动

先启动当前分支对应的 CampusClaw Runtime，再启动前端：

```bash
cd frontend
npm ci
npm run dev
```

浏览器打开 `http://localhost:5173`。开发服务器默认把 `/campusclaw-service` 代理到
`http://localhost:8080`。

## HTTP 1.38.0 语义

- Path、Query、JSON 请求/响应和 SSE `data` 字段统一使用 lowerCamelCase，不接受旧 snake_case 别名。
- 初始消息体只发送 `message` 和可选 `fileIds`，不发送旧 `type` 字段。
- SSE 是提交消息的同一个响应；收到响应头后 Composer 即进入 running，可继续提交控制消息。
- Composer 草稿不在响应头到达时清空；只有 SSE `user.message` 或 GET Events 历史确认本次新 Entry 后才清空。
- Steer 不硬中断当前模型或工具，而是在下一次模型调用前优先送达；FollowUp 在本次执行
  自然结束时继续。
- Steer/FollowUp 的 `202` 没有公共控制项 ID，界面只使用本地临时 key，不把它伪装为
  Runtime 身份；刷新后不承诺恢复未送达队列。
- 网络错误后不自动重放初始消息、Steer 或 FollowUp。断流后先读 Session/Events 对账；若仍无法确认则保留草稿，发布 `OUTCOME_UNCERTAIN` 并提示不要重复提交。
- 流结束后从 GET Events 第一页读取权威持久化历史，原样跟随 `nextPage`，按 `entryId` 去重。

## 质量命令

```bash
npm run typecheck
npm test
npm run build
npm audit --audit-level=high
```

设计依据见 [CampusClaw 产品前端体验设计](../docs/designs/campusclaw-frontend.md) 和
[ADR-0020](../docs/decisions/0020-campusclaw-product-frontend-boundary.html)。
