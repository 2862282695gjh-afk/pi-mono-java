# ADR-0018：CampusClaw 产品前端隔离 Runtime 调试协议

| 属性 | 值 |
|---|---|
| 状态 | Accepted |
| 日期 | 2026-08-20 |
| 分析源码基线 | `7b655487df80685187ed0193f6578060615f7d43` |
| HTTP 契约基线 | CampusClaw Runtime HTTP 1.37.0 |
| 关联设计 | [CampusClaw 产品前端体验设计](../designs/campusclaw-frontend.md) |
| 架构图 | [产品前端边界](../designs/campusclaw-frontend/frontend_product_boundary.svg) |
| PlantUML 源码 | [`frontend_product_boundary`](../designs/campusclaw-frontend/diagram.puml#L1) |

## 背景

改造前的 `frontend/src/App.vue` 和 `frontend/src/composables/useRuntimeApi.ts` 把
Service URL、JWT/APPKEY、Session ID、ETag、原始 SSE Event 和 JSON payload 作为主界面
元素。该形态完整覆盖内部 Runtime operation，适合接口联调，但用户任务实际是选择 Agent、
管理会话、发送消息、查看活动和控制执行。

HTTP 1.37.0 同时确认：Session Runtime 是内部资源，浏览器生产链路应由 mate-service 完成
认证、授权、公共 Chat 到内部 Session 的映射、错误脱敏和 Event 投影，不能把内部 SSE
字节透明转发给浏览器。

## 已考虑方案

### 继续扩展调试台

改动最少，但产品信息架构继续围绕接口，且内部身份、并发版本和事件模型越过浏览器边界。
拒绝。

### 产品页面隐藏高级字段后继续直连 Runtime

能够较快完成视觉改造，但隐藏 DOM 元素不能解决浏览器认证、授权、内部资源身份和安全
Event Schema。拒绝作为生产架构。

### 产品 UI 经公共 bridge，直接 Runtime adapter 仅作过渡

用户模型稳定，生产责任清晰；在公共接口完成前，当前仓库仍能使用不接收秘密凭据的 adapter
验证 UI 和 HTTP 1.37.0。接受。

## 决策

- 生产浏览器只调用 mate-service 公共 HTTP/SSE。mate-service 终止认证、执行授权，维护
  公共 Chat 到内部 Session 的映射，并投影浏览器安全 Event。
- 产品主界面只展示 Agent、会话、消息、附件、模型、深度思考、工具活动和粗粒度执行状态。
- `session_id`、ETag、Runtime 凭据、原始 JSON、SSE frame 和内部错误不得进入产品主流程。
- Steer、FollowUp、Abort 的产品文案分别为“调整方向”“加入队列”“停止”。desktop 当前
  模式默认 `steer`；`Cmd/Ctrl+Shift+Enter` 只反转本条消息，不修改默认设置。
- “调整方向”不表示硬中断当前模型调用或工具；它只在当前 Assistant Turn 及工具结束后、
  下一次模型调用前优先送达。
- 工具 started/completed/result 合并为一个可展开活动卡；持久化 completed/result 覆盖瞬时预览。
- 网络断开后禁止自动重放初始消息、Steer 或 FollowUp；客户端从第一页重新读取持久化历史并去重。
- 当前 `frontend/src/composables/useRuntimeApi.ts` 是架构变化完成前的过渡 adapter：只读取
  非秘密环境配置，不提供 JWT/APPKEY 编辑器，不把它描述为生产安全边界。
- Agent 目录、持久化 Chat 列表与标题、附件上传、模型目录、可恢复队列项和 public SSE
  属于目标态公共契约；对应实现不存在时必须明确标为 target-only，不伪造接口。

## 差异分类

| 差异 | 分类 | 设计理由 |
|---|---|---|
| 产品 UI 隐藏 Runtime 身份、凭据、ETag 和原始事件 | 安全加固 | 避免内部协议和秘密信息进入浏览器产品边界 |
| 单一当前跟进模式和单次快捷键反转 | 产品约束 | 与 Codex desktop 可观察行为对齐，减少常驻选择负担 |
| 工具生命周期合并为活动卡 | 产品约束 | 保留执行透明度，删除协议噪音 |
| 生产链路引入 mate-service 公共 bridge | 架构变化 | 集中承担认证、授权、映射、脱敏和 Event 投影 |
| 公共 bridge 完成前保留直接 Runtime adapter | 架构变化 | 支持当前前后端并行开发，但不扩大为生产承诺 |

## 结果

正面结果是主界面围绕用户任务、Runtime 可以独立演进，且前端已经能够按 HTTP 1.37.0
验证请求级 SSE、历史恢复和运行控制。代价是还需要逐项设计 Agent、Chat、Attachment、
Model Catalog、队列项和 public SSE 契约，并在生产集成时替换过渡 adapter。

回滚产品 UI 不得把旧调试台重新定义为生产安全边界。过渡 adapter 被替换后，Runtime
诊断能力应进入独立的内部构建或受控工具，而不是重新出现在产品首页。
