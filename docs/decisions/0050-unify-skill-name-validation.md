# ADR-0050：统一 Skill 领域常量与名称校验

- Status：Accepted；常量文件归属部分由 [ADR-0051](0051-centralize-shared-claw-constants.md) 替代，严格名称约束仍有效。
- Date：2026-09-04
- 文档版本：1.3.0
- 决策类别：产品约束、架构归属调整
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

领域归属复核基线 `cf4f929af2d407d3673bfabac0c311831fb570c7` 中，ID 正则仍留在
`common/identifier/ResourceIdentifierPatterns.java`，与名称规则分散在两个类；已有 AGENTS.md
明确要求共享正则归属于领域类，用户再次指出该遗漏。1.1.0 将 ID 与名称规则统一到 SkillPatterns。

共享常量复核基线 `c0e2915230e73f626a5ede99f0fc3b3412bbff0b` 中，`skill/Skill.java` 仍持有
`MAX_DESCRIPTION_LENGTH`、`MAX_FILE_BYTES`；Loader、Runtime、提示词加载和命令发现分别声明
目录名、文件名或命令前缀。AGENTS.md 已要求共享常量按领域集中，用户拒绝将该要求缩成正则归属。
1.2.0 将这些常量与正则统一至 SkillConstants；具体源文件与符号见运行目录设计 6.2。

## Decision

名称只允许 1 至 64 个 ASCII 小写字母、数字以及单个分隔连字符，禁止首尾和连续连字符。
按后续 ADR-0051，所有 Skill 共享常量集中于底层 `common.constant.ClawConstants.Skill`。名称由 `NAME_REGEX`、
`NAME_PATTERN`、`MAX_NAME_LENGTH` 和 `isValidName` 表达，加载和发现共同复用；
ID 由 `ID_REGEX` 与 `ID_PATTERN` 表达，Runtime、MateServiceClient 和 HttpMateToolClient 共同复用。
删除 LEGACY/STRICT 两套模式及方法、SkillNamePatterns 旧类及 ResourceIdentifierPatterns 中的
Skill ID 定义，不提供旧规则开关、转发别名或自动改名。ID 格式与原先的十六进制大小写规则保持不变。

同类集中名称上限 64、描述上限 1024、文件上限 1 MiB、`skills` 目录名、`SKILL.md` 与 `skill.json`
文件名、`skill:` 命令前缀。消费者直接引用，删除 SkillPatterns、SkillConstants 旧类和业务类中的对应常量。
`Skill` 只承载元数据，不持有共享常量；错误翻译和非法文件处理仍由现有 Loader、Runtime 及提示词调用方负责。
本次不改变 Child Agent 名称规则、ID 格式、HTTP 结构或 Skill 命令执行的交付边界。

## 选项与取舍

| 选项 | 优点 | 缺点与结论 |
|---|---|---|
| 继续两套规则 | 保留基线放行行为 | 无已确认的兼容需求，并造成加载与命令发现标准不一致；拒绝。 |
| 统一宽松规则 | 各入口标准相同 | 继续接受用户明确禁止的非法名称；拒绝。 |
| 自动去除或折叠连字符 | 部分非法输入可继续处理 | 改变绑定与目录身份，可能产生重名；拒绝。 |
| 统一严格规则 | 一个定义来源，非法名称在加载时即被拒绝 | 非法数据必须由来源方修正；选择。 |

按字段分别设立名称类和通用资源 ID 类虽能避免字面量重复，仍使同一 Skill 领域的规则分散维护；
原方案在 SkillConstants 内区分 ID、名称、限制和文件约定；后续 ADR-0051 将该完整分组迁入 ClawConstants.Skill。
同一领域的共享常量一起维护，使数据类型、加载器与调用方无需分别声明规则。

## Consequences

合法名称行为保持一致。非法远端 Skill 不能发布，失败刷新保留旧有效目录；非法本地缓存不再命中，
重新拉取仍不合法时返回失败。直接提示词扫描跳过非法 Skill，命令发现过滤非法名称。
不新增持久化结构、网络调用或可配置策略。预编译 Pattern 继续复用。
常量归属调整保留各数值、文件布局、命令名称和校验触发位置，不改变上述名称收紧之外的运行行为。

## 验证

覆盖合法及空名称、64/65 字符边界、首尾和连续连字符、文件和目录回退名称、首次发布失败、
刷新保留旧目录、缓存拒绝与重建、提示词过滤和有实体文件时的命令过滤。
ID 定义迁移复用两类 Mate 客户端的出站路径与非法 ID 拒绝测试。
运行聚焦 JUnit、格式及 Checkstyle；同步 campusclaw 镜像并明确报告公司依赖验证是否可执行。

## Related

- [Agent 与 Skill 受管运行目录](../designs/agent-skill-runtime.md)
- [Skill 名称校验 PlantUML](../designs/skill-name-validation/diagram.puml#L1)

## 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 1.3.0 | 2026-09-04 | 标记常量文件归属由 ADR-0051 替代；严格名称约束继续有效。 |
| 1.2.0 | 2026-09-04 | 扩展为 SkillConstants，统一共享限制、目录和文件名、命令前缀，使 Skill 仅承载数据。 |
| 1.1.0 | 2026-09-04 | 将 Skill ID 和名称的所有正则统一到 SkillPatterns，删除分散定义及旧类。 |
| 1.0.0 | 2026-09-04 | 按用户纠正移除非法名称兼容，统一 Skill 名称规则。 |
