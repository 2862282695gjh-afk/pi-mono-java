# ADR-0050：统一 Skill 名称校验

- Status：Accepted
- Date：2026-09-04
- 文档版本：1.0.0
- 决策类别：产品约束
- 源码观察基线：`2c2092f2fa764a7aa7b47da841299f8866d7a151`

## Context

基线中 `SkillLoader.validateName` 使用 `SkillNamePatterns.LEGACY`，命令发现使用
`SkillCommandSource.resolved` 中的 `isStrictValid`。因此 `-pdf`、`pdf-`、`pdf--tools`
能通过受管目录发布、缓存加载及提示词加载，却不能生成命令。

相关源码均位于 `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`：
`skill/SkillLoader.java`、`skill/SkillNamePatterns.java`、`runtime/AgentRuntimeManager.java`、
`runtimeapi/agent/RuntimeAgentPromptLoader.java` 与 `runtimeapi/service/command/SkillCommandSource.java`。
完整来源、符号与加载链路见[运行目录设计 6.2](../designs/agent-skill-runtime.md#62-skill-名称统一校验)。

2026-09-04 用户明确拒绝非法名称兼容。原实现放行和人为兼容测试并不能证明产品需要保留此行为。
本决策替代运行目录设计 3.4.3 中的名称兼容策略。

## Decision

名称只允许 1 至 64 个 ASCII 小写字母、数字以及单个分隔连字符，禁止首尾和连续连字符。
所有名称规则集中于 Skill 领域的 `SkillNamePatterns`；加载和发现共同调用 `isValid`。
删除 LEGACY/STRICT 两套模式及方法，不提供旧规则开关、方法别名或自动改名。

`Skill` 保持元数据承载职责；错误翻译和非法文件处理仍由现有 Loader、Runtime 及提示词调用方负责。
本次不改变 Child Agent 名称规则、ID 格式、HTTP 结构或 Skill 命令执行的交付边界。

## 选项与取舍

| 选项 | 优点 | 缺点与结论 |
|---|---|---|
| 继续两套规则 | 保留基线放行行为 | 无已确认的兼容需求，并造成加载与命令发现标准不一致；拒绝。 |
| 统一宽松规则 | 各入口标准相同 | 继续接受用户明确禁止的非法名称；拒绝。 |
| 自动去除或折叠连字符 | 部分非法输入可继续处理 | 改变绑定与目录身份，可能产生重名；拒绝。 |
| 统一严格规则 | 一个定义来源，非法名称在加载时即被拒绝 | 非法数据必须由来源方修正；选择。 |

## Consequences

合法名称行为保持一致。非法远端 Skill 不能发布，失败刷新保留旧有效目录；非法本地缓存不再命中，
重新拉取仍不合法时返回失败。直接提示词扫描跳过非法 Skill，命令发现过滤非法名称。
不新增持久化结构、网络调用或可配置策略。预编译 Pattern 继续复用。

## 验证

覆盖合法及空名称、64/65 字符边界、首尾和连续连字符、文件和目录回退名称、首次发布失败、
刷新保留旧目录、缓存拒绝与重建、提示词过滤和有实体文件时的命令过滤。
运行聚焦 JUnit、格式及 Checkstyle；同步 campusclaw 镜像并明确报告公司依赖验证是否可执行。

## Related

- [Agent 与 Skill 受管运行目录](../designs/agent-skill-runtime.md)
- [Skill 名称校验 PlantUML](../designs/skill-name-validation/diagram.puml#L1)

## 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 1.0.0 | 2026-09-04 | 按用户纠正移除非法名称兼容，统一 Skill 名称规则。 |
