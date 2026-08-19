# CampusClaw HTTP + SSE Frontend

Vue 3 + TypeScript + Vite 调试客户端，直接调用已确认的
`/campusclaw-service/v1` Session Runtime 接口。

它覆盖以下工作流：

- 创建、恢复、读取和删除 Session；
- 查询可用 `model_id`、切换模型和开关深度思考；
- 通过 `POST /sessions/{session_id}/events` 提交 `user.message` 并解析该请求返回的 SSE；
- 查询当前分支对话 Entry 历史；
- 在活动执行中发送 Steer、FollowUp 或 Abort；
- JWT Header 与 AppKey Header 两种凭据形态。

## 启动

先按根目录文档配置 openGauss、Agent 根目录和模型凭据，然后正常启动 Spring Boot 服务：

```bash
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar
```

另开终端启动前端：

```bash
cd frontend
npm ci
npm run dev
```

浏览器打开 `http://localhost:5173`。开发服务器把
`/campusclaw-service` 代理到 `http://localhost:8080`，因此页面默认使用同源地址，
不会要求后端开放跨域访问；需要连接其他环境时再填写 Service URL。

## 重要语义

- SSE 是单次请求范围的响应流，收到 `stream.end` 或 `stream.error` 后服务端结束响应。
- 点击 “Disconnect SSE client” 只断开当前前端读取，不会中止已经被服务端接受的执行。
- 真正中止执行必须调用 Abort 接口。
- Steer 与 FollowUp 不另开事件订阅，它们加入当前执行队列，后续事件仍由原 SSE 返回。
- 页面只在内存中保存凭据，不写入浏览器持久化存储。

## 质量命令

```bash
npm run typecheck
npm run build
npm audit --audit-level=high
```
