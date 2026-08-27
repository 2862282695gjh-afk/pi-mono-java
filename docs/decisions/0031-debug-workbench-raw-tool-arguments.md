# ADR-0031：调试工作台直接展示工具原始参数

| 属性 | 值 |
|---|---|
| 状态 | Accepted |
| 日期 | 2026-08-27 |
| 分析基线 | `PR #179@e4ef301cc7cbf93c4a651c060e2c48ebcec74cbd` |
| 源码证据 | `frontend/src/projectors/runtimeEventProjector.ts` 的 `projectToolArguments`、`appendToolArgument`、`formatToolValue`；`frontend/src/components/ToolActivity.vue` |
| 修订决策 | [ADR-0030](0030-runtime-thinking-and-activity-rich-text.md) 中的 Tool 参数脱敏和产品前端定位 |

## 背景

`assistant.message.completed` 的 `tool_call.arguments` 已经包含 Runtime 实际执行参数。此前前端按
面向业务用户的产品界面设计，在投影层隐藏凭据形态字段、内部 ID 和 Bearer/JWT 形态值，并把
绝对路径缩短为最后一段。

用户确认当前前端定位是内部调试工作台，而不是生产产品前端。调试人员需要核对模型实际生成并
提交给工具的参数；脱敏会丢失定位参数绑定、路径、身份传播和下游契约问题所需的证据。

## 决策

- 工具详情标题使用“原始输入参数”，按现有结构化键值行展示 Runtime 返回的参数。
- 删除敏感字段名、敏感值、内部 ID 和绝对路径的前端脱敏；字符串只受显示长度预算约束。
- 对象和数组继续按点路径与索引扁平化，最多 12 行、3 层、每值 240 字符。该预算用于页面
  稳定性，不声称提供信息安全边界。
- Tool result、Assistant 和 Thinking 继续使用安全 Markdown token-to-VNode 渲染；取消参数
  脱敏不授权 raw HTML、远程图片或危险链接进入活动 DOM。
- 工作台只能部署在受控开发或内部验证环境。未来生产产品前端必须通过独立公共 bridge 重新
  定义认证、授权、审计、字段最小化和错误投影，不得继承本工作台的原始参数可见性。

## 有意差异

- **产品约束：**目标用户从业务用户调整为 Runtime 开发者和 Agent 行为调试人员。
- **架构变化：**Tool 参数投影从“脱敏摘要”调整为“有页面预算的原始参数 viewer”。Runtime
  事件接口和持久化格式不变。
- **安全边界变化：**工作台明确接受工具参数中可能出现凭据、内部 ID 和本地路径的风险；安全
  Markdown 与 DOM 预算仍保留，但不能被描述为数据脱敏。

## 影响

调试人员可以核对完整路径、Authorization、Session/Agent ID 等参数，定位发现到调用链路的
契约问题。代价是浏览器 DOM 和截图可能包含敏感信息，因此访问控制、运行环境和调试材料流转
必须由部署侧负责。

## 验证

- 投影测试断言绝对路径、Token、Authorization 和内部 ID 保持原值，同时保留结构化展开。
- 浏览器 fixture 使用显式测试值，断言“原始输入参数”区域可见完整路径与凭据形态参数。
- 桌面和 390 px 移动端均不得产生页面级横向溢出。

## 相关资料

- [CampusClaw 前端调试工作台设计](../designs/campusclaw-frontend.md)
- [Runtime 事件投影图](../designs/campusclaw-frontend/runtime_event_ui_projection.svg)
- [PlantUML 源码](../designs/campusclaw-frontend/diagram.puml)
