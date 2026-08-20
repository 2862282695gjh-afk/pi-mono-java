# CampusClaw 高保真视觉稿 ImageGen Prompts

> 生成方式：Codex 内置 imagegen
>
> 用例分类：`ui-mockup`
>
> 日期：2026-08-20

## 常规对话

参考图：`low-fidelity.png`，只作为信息架构和布局参考。

```text
Use case: ui-mockup
Asset type: shippable desktop web application mockup, 16:10 landscape
Primary request: Create a high-fidelity CampusClaw AI agent workspace for real end users. Use Image 1 only as the information-architecture and layout reference; focus on the large conversation screen at left, not the three-screen presentation board.
Subject: A polished desktop product screen with a 248 px left sidebar for new conversation and grouped recent sessions; a spacious central conversation canvas; a compact top bar showing the selected Agent, session status, deep-thinking state, and current model; an anchored bottom message composer. Show one user message with an attached spreadsheet and one assistant response summarizing three order anomalies. Merge tool execution and tool result into one compact expandable in-conversation activity card.
Exact visible product text, render verbatim where used: "CampusClaw", "新建会话", "今天", "订单异常分析", "运营分析 Agent", "深度思考", "Claude Sonnet 4.5", "空闲", "请分析这些订单文件，并列出异常项。", "订单明细.xlsx", "我发现了 3 类需要关注的异常", "已读取并分析 1 个文件", "继续提问，或输入你希望 Agent 执行的任务…", "发送".
Style/medium: realistic, shippable SaaS product UI, crisp typography, refined spacing, understated depth, no device frame, not concept art.
Composition/framing: full browser application viewport, straight-on, 1440x900 design proportions; sidebar and main canvas fully visible; generous whitespace; no right settings panel.
Color palette: warm off-white canvas, soft sand sidebar, deep graphite text, restrained coral-orange primary action and active accents, muted sage success status; high contrast and accessible.
Typography: modern humanist sans-serif with Chinese glyph support; clear 14-16 px body hierarchy; avoid tiny decorative text.
Constraints: hide Service URL, credentials, X-HW-ID, JWT, APPKEY, session_id, ETag, raw JSON, SSE frames, developer fields, and API terminology; no charts; no marketing hero; no photos; no gradients; no glassmorphism; no neon; no watermark; no unrelated logos; do not add a right inspector; keep controls practical and implementation-ready.
```

输出：`high-fidelity-conversation.png`

## 执行中

参考图：`high-fidelity-conversation.png`，作为视觉系统和布局参考。

```text
Use case: ui-mockup
Asset type: companion high-fidelity desktop web application screen
Primary request: Create the execution-in-progress state of the same CampusClaw product shown in Image 1.
Input images: Image 1 is the visual system and layout reference. Preserve its exact overall shell, warm off-white and sand palette, typography, spacing, sidebar, top bar, icon language, and 16:10 straight-on application framing.
State changes: The same "运营分析 Agent" session is now running. In the top bar, change the green idle status to a restrained amber running status labeled "执行中", keep "深度思考" and "Claude Sonnet 4.5", and add a practical outlined stop control labeled "停止".
Conversation content: Show the user message "检查这批订单，并给出处理建议。" and an assistant activity area with one refined progress card labeled "正在读取订单明细并检查异常…" plus three compact sequential task rows: "读取订单明细.xlsx" completed, "检查价格与数量异常" in progress, "生成处理建议" waiting. Keep the interface calm, not a monitoring dashboard.
Composer behavior: Replace the normal idle composer mode with two clear compact mode chips inside the composer: selected "立即调整方向" and secondary "完成后继续". Placeholder text "补充新的要求…". Primary action text "加入执行". These are productized names for execution control.
Style/medium: realistic, shippable SaaS UI, same as Image 1, crisp Chinese typography, restrained depth.
Constraints: change only the session state, conversation content, progress card, and composer controls described above; preserve the sidebar structure and product identity from Image 1. Hide Service URL, credentials, X-HW-ID, JWT, APPKEY, session_id, ETag, raw JSON, SSE frames, Steer, FollowUp, Abort, developer fields, and API terminology. No charts, no right inspector, no dark theme, no gradients, no glassmorphism, no watermark, no unrelated logos.
```

输出：`high-fidelity-running.png`
