# Showcase 业务确认开发与演示

版本：1.0.0；日期：2026-09-17。仅用于 `codex/showcase-confirm-with-user` 分支，不合入 main。

实现起点为 `19870445a3a8bd559919fe24580330be8227488f`。设计依据为
[showcase 业务确认设计](https://github.com/superheromeZzh/pi-mono-java-design/blob/106f9f5a79af78386717371e1ba52330d26013bf/01-总体架构/06-CampusClaw计划模式/showcase-confirm-with-user.md)；
事件字段、原样载荷与答案规则以该设计链接的共享契约为准。本说明记录实现与演示操作，不复制公共接口 Schema。

## 演示配置

部署参数 `campusclaw.showcase.confirm-with-user-enabled=true` 或环境变量
`CAMPUSCLAW_SHOWCASE_CONFIRM_WITH_USER_ENABLED=true` 启用临时适配，缺省 false。
后端与该分支前端必须配套发布；开关在新执行准备时固定。启用后 Runtime 默认与显式工具列表均移除 AskUserQuestion，保留旧问题历史读取。

独立服务可用以下参数启动，数据库、模型及 Mate 连接仍使用已有部署配置：

```bash
java -jar modules/coding-agent-cli/target/campusclaw-agent.jar \
  --campusclaw.showcase.confirm-with-user-enabled=true
```

公司镜像使用相同 Spring 属性，具体启动入口沿用公司部署流程。该分支没有新增数据库表、字段或 Maven/npm 依赖。

在业务 Agent 的可信工具绑定中配置精确名称 `confirm_with_user`，权限必须为 allow，发现结果必须唯一。
输入 label 为必填字符串，context 为可选对象；context 内部不约定字段。模型仍通过 CallMateTool 调用它。
原始 label/context 在问题事件及界面保持 JSON 值与结构相同，省略 context 和空对象保持区别。
确认只在 Runtime 根执行中等待用户回答；Child/Cron 不执行这个本地交互，也不降级远调。

业务 Agent 的 BASE 和自定义 PLAN.md/BUILD.md 如有旧提问指令，应按以下行为更新，保留业务身份和其他模式约束：

```text
需要用户提供信息或确认业务内容时，使用 CallMateTool 调用 confirm_with_user，传入 label 和可选 context。label/context 保留实际业务内容；不要调用 AskUserQuestion。提问时该次 Assistant 只发出这一项工具调用，等待工具返回真实 content 后再继续。Subagent 把需要用户回答的问题交给父 Agent；Cron 汇报缺失信息。
```

## 源码接线

下列 Java 路径相对 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`。

| 职责 | 路径与符号 |
|---|---|
| 开关与清单过滤 | `tool/question/ShowcaseConfirmationProperties`、`tool/builtin/DefaultConfiguredToolAssembler#assemble` |
| 每执行适配器与可信绑定 | `runtimeapi/runtime/RuntimeSessionEngineRegistry#createSession`、`tool/question/ShowcaseConfirmationAdapter#prepare` |
| 原问题等待与失败清理 | `runtimeapi/runtime/RuntimeActiveExecution#beforeToolCall`、`runtimeapi/event/RuntimeEventProjector#beginQuestion` |
| 本地工具结果 | `tool/mate/CallMateTool#execute`、`ShowcaseConfirmationAdapter#await` |
| 原样事件及历史 | `runtimeapi/event/RuntimeEntryCodec`、`RuntimeCommittedEventFactory`、`CommittedEventProjection` |
| 固定答案槽校验 | `runtimeapi/event/RuntimeUserQuestionService#normalizeCurrent`、`tool/question/QuestionRequestParser#normalizeTextAnswer` |
| 前端卡片与提交 | `frontend/src/components/QuestionCard.vue`、`runtime/questionInteraction.ts`、`projectors/runtimeEventProjector.ts`（前端路径相对仓根） |

## 人工验收步骤

1. 新建执行，确认模型工具清单无 AskUserQuestion，其他业务工具仍可调用。
2. 让模型调用 confirm_with_user，分别覆盖省略 context、空对象及嵌套数组/null/空白值。
3. 核对 agent.question 与原工具 args 的 label/context 深相等；原 SSE 在 confirming 后结束，Session 仍为 running，界面显示等待回答。
4. 核对界面只显示该问题的 label/context、回答框和确认按钮；普通 CallMateTool 仍显示。
5. 输入具体业务数据并点击确认。核对 user.question_answer 回执先于原工具结果，结果 content 为规范化后的真实回答，原根继续执行。
6. 覆盖空回答、重复点击、停止、断线恢复、其他客户端已回答和再次提问；旧题不可重复回答，草稿不串题，写请求不自动重放。
7. 分别检查宽屏和窄屏布局；Enter 换行，输入法确认不触发提交，长 context 和按钮可滚动到达。

工具绑定为 deny/ask、缺失或歧义时应明确失败，不产生问题等待，不调用远端同名工具。
用户回答不批准计划、不改变 mode、不创建新根，也不授予其他业务操作权限。

## 退出演示

停止接受新展示执行，完成或中断已有等待，再关闭开关或切换构建。
含 label/context 的历史继续要求联合载荷解码；不把新历史交给只认识 questions 的旧二进制，不清表回退。

## 验证记录

2026-09-17 对最终代码完成以下验证。未新增或修改图源，沿用设计分支的目标结构与时序图。

| 范围 | 结果 |
|---|---|
| 后端定向回归 | JDK 21，9 类 74 项，0 失败、错误、跳过；覆盖适配器、旧 CallMateTool、工具装配、等待清理、事件投影、历史、答案及 HTTP 输入边界 |
| 实际 HTTP / AgentLoop / openGauss | 4 项通过，独立合成测试库；确认闭环、原根续跑、等待无后续模型请求/远端执行、context 变体、4096/4097 答案边界、停止及开关关闭后的旧问答 |
| 前端 | 34 文件 266 项测试通过，类型检查与生产构建通过 |
| 浏览器 | 1440px/390px 原样问题、可滚动按钮、空回答提示、Enter 换行、确认续跑及刷新恢复通过；未模拟系统输入法候选窗 |
| 前后端实际载荷 | 实际 JAR 的 10 个 live 帧和 9 个历史帧通过前端 decoder/projector；保留 label/context，只有一张关联问题卡 |
| 静态规范 | Spotless check、main/test Checkstyle、29 个修改/新增源 Java 文件的 AST 方法长度检查、git diff --check 通过 |
| 测试质量 | 扫描 errors=0；命名启发式警告及既有 catch 重抛提示已人工核对，无吞异常或恒真断言 |
| 公司镜像 | sync-campusclaw.sh --no-verify 与布局检查通过；公司构建无法解析 NativeParent:26.0.0-SNAPSHOT，未进入源码编译 |

主模块 package 成功，产物位于 `modules/coding-agent-cli/target/campusclaw-agent.jar`；前端构建产物位于 `frontend/dist`。
超长答案的实际 HTTP 测试暴露了 POST events 方法校验错误落到 500 的缺口；本分支已映射为 `INVALID_EVENT_REQUEST` / HTTP 400，并补回归。

独立 IT 类为 `RuntimeShowcaseConfirmationOpenGaussIT`。运行时通过 Maven system properties 提供
`runtime.it.jar`、`gaussdb.it.url`、`gaussdb.it.username`、`gaussdb.it.password`；数据库必须使用专用测试库，不能指向业务数据。

自动化使用受控模型、Mate 元数据桩和独立 openGauss 测试库，浏览器使用合成 fixture；这些结果不等同于真实业务 Agent、企业 Mate 页面及生产环境联调。业务工具真实绑定、业务提示词发布和公司环境构建仍需在展示环境完成。
