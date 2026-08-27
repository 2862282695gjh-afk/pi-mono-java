# ADR-0030：保持 Runtime 事件并统一活动富文本

| 属性 | 值 |
|---|---|
| 状态 | Superseded in part by [ADR-0031](0031-debug-workbench-raw-tool-arguments.md) |
| 日期 | 2026-08-27 |
| 分析基线 | `PR #179@8c5f3462d745ec0c5146d55dcb62108ac2a33282` |
| 后端源码证据 | `RuntimeEventProjector.java` 的 `emitThinkingDelta` / `persistThinking`；`RuntimeEntryCodec.java` 的 `thinkingEntry` / `appendThinkingPayload` |
| 前端实现路径 | `runtimeEventProjector.ts` 的 `projectThinkingEvent`；`SafeRichText.ts`；`ThinkingDisclosure.vue`；`ToolActivity.vue`；`richText.ts` |
| 替代决策 | [ADR-0027](0027-assistant-safe-rich-text.html)、[ADR-0028](0028-agent-activity-disclosure-and-o1-brand.html)、[ADR-0029](0029-agent-round-actions-and-activity-panel.html) |
| 后续修订 | [ADR-0031](0031-debug-workbench-raw-tool-arguments.md) 仅替换前端定位与 Tool 参数可见性；Thinking/富文本/活动框决策继续有效 |

## 背景

当前 Runtime 已通过 `assistant.thinking.delta` 的 `data.delta.text` 发送实时原始推理，并通过
持久化 `assistant.thinking.completed` 的 `data.content.text` 发送权威全文。它没有
`thinkingDisplayTitle` 或 `thinkingDisplaySummary`。0.9.0 前端预留了这些目标字段，因此真实
环境只能显示占位。

用户明确要求不修改事件接口，当前前端先直接展示原始推理片段；同时要求 Assistant、Thinking
和 Tool 输出都考虑富文本。该决策替换此前“原始 Thinking 永不进入 DOM”和“仅 Assistant
使用 Markdown”的约束，但保留 O1 品牌、活动独立虚线框、工具参数脱敏与整轮单一复制入口。

> **已接受的信息暴露：**原始 Thinking 可能包含模型内部判断、用户数据或工具上下文。本决策
> 只授权当前过渡前端展示 Runtime 已返回的内容，不授权新增日志、持久化副本、复制入口或向
> 未授权用户扩大可见范围。

## 决策驱动因素

- 不新增或改变 `assistant.thinking.*` 事件名称、字段或持久化结构。
- 流式内容与刷新后的历史必须一致，completed 是权威全文。
- Assistant、Thinking 与 Tool result 中的表格、列表、代码和强调应具有一致可读性。
- 扩大内容范围不能扩大 HTML、图片请求、危险协议或伪交互的执行权限。

## 考虑的方案

### 继续等待 display 摘要字段

优点是信息暴露最小；缺点是现有 Runtime 没有这些字段，主界面持续只有占位，也不符合用户
要求，因此拒绝。

### 直接以纯文本显示原始 Thinking 与 Tool result

优点是实现简单且事件接口不变；缺点是 Markdown 表格、列表和代码仍难以阅读，三类文本体验
不一致，因此拒绝。

### 原始 Thinking 与 Tool result 复用受限 Markdown 投影

优点是不改事件接口，流式与历史一致，三类内容共享一套 allowlist、预算和降级策略。缺点是
原始 Thinking 对终端用户可见，Tool 输出中的 Markdown 会获得安全外链等有限交互能力。
本方案被接受。

## 决策

- `projectThinkingEvent` 按 `assistantEntryId + contentIndex` 聚合 turn；delta 顺序追加
  `data.delta.text`，completed 使用 `data.content.text` 替换已有预览。started 只创建等待状态。
- Thinking disclosure 保持运行中展开、完成后折叠，并显示“原始推理”标签。没有 Thinking
  事件时不创建活动框；事件无文本时显示状态化空内容说明。
- `SafeRichText` 由原 Assistant 渲染器泛化而来，供 Assistant、Thinking 与 Tool result
  复用；三者都支持受限 Markdown、流式稳定尾块、表格/代码局部滚动与预算回退。
- 原始 HTML 保持不可执行；Markdown 图片只显示占位；只有无凭据的绝对 HTTP/HTTPS 链接
  可点击；相对链接和危险协议保持不可点击。
- Tool 输入参数继续使用结构化键值，不作为 Markdown；参数行数、深度、长度、凭据、内部 ID
  与绝对路径防护保持不变。
- Thinking 与每个 Tool 继续使用各自独立暖灰虚线框。整轮复制继续只聚合 Assistant Markdown，
  不复制 Thinking 原文、Tool 参数或 Tool result。

## 有意差异

- **产品约束：**当前过渡前端按用户要求直接显示 Runtime 原始 Thinking；“原始推理”标签用于
  区别面向用户的最终答案。
- **安全加固：**内容范围扩大，但执行权限不扩大。Thinking 与 Tool result 必须经过与
  Assistant 相同的 token allowlist 和 URL policy，禁止直接 HTML。
- **架构变化：**删除不存在的 display 字段依赖；把 Assistant-only 渲染器泛化为跨活动内容
  渲染器。Runtime HTTP/SSE 契约保持不变。

## 影响

正面影响是现有 Runtime 无需改造即可提供真实分析内容，刷新历史与流式预览一致，并且所有
生成文本中的结构化 Markdown 使用同一视觉和安全边界。

负面影响是用户可能看到冗长、反复或包含内部上下文的原始推理；高频 Markdown 解析会增加
前端开销；Tool 输出中的安全外链需要继续依赖 URL policy。生产 bridge 仍需在保持事件语义
的前提下评审权限、审计、内容保留和可能的部署级关闭策略，这些后续不阻塞当前过渡前端。

## 验证

- `runtimeEventProjector.test.ts` 覆盖多 delta 聚合、completed 权威替换、历史原文和无事件状态。
- `richText.test.ts` 覆盖语法、HTML/图片/URL 边界、流式尾块与预算降级。
- 浏览器覆盖 Thinking 和 Tool result 的 Markdown、独立框及桌面/移动溢出。
- 前端必须通过 `npm test`、`npm run typecheck`、`npm run build`、高危依赖审计和浏览器
  console 检查。

## 相关资料

- [CampusClaw 产品前端体验设计](../designs/campusclaw-frontend.md)
- [常规对话 v11](../designs/campusclaw-frontend/high-fidelity-conversation-v11.png)
- [执行中 v11](../designs/campusclaw-frontend/high-fidelity-running-v11.png)
- [对话活动安全富文本渲染图](../designs/campusclaw-frontend/assistant_rich_text_rendering.svg)
- [PlantUML 源码](../designs/campusclaw-frontend/diagram.puml#L72)
