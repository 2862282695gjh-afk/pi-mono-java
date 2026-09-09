# CampusClaw 共享常量

> 文档版本：1.0.0
>
> 状态：Implemented in PR #219
>
> 日期：2026-09-04
>
> 变更前源码基线：`ee3fdb4893228045f06b9b1d1b3b3bb505812c73`

## 1. Context

共享业务常量原先按领域分文件，部分固定值仍放在业务对象或服务类中。用户确认改为单文件维护，
同时将 common 提升到与 ai、agent、cron、codingagent 同级的包。此变更属于**架构调整**，
替代“按领域分常量文件”的组织规则；保留严格 Skill 名称规则、常量值和原有校验触发位置。

## 2. 源码观察与迁移清单

以下路径均相对于仓库根目录，描述上述提交的观察行为。目标类和模块在该提交中尚不存在。

| 源文件与符号 | 目标分组 | 决策理由 |
|---|---|---|
| `modules/ai/src/main/java/com/campusclaw/ai/utils/CampusClawHome.java`：HOME_PROPERTY、HOME_ENV、CONFIG_DIR_NAME、AGENT_SUBDIR | `ClawConstants.Home` | 统一用户级目录解析所使用的产品固定名称。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/common/identifier/ResourceIdentifierPatterns.java`：AGENT、TOOL、SESSION 的 ID_REGEX/ID_PATTERN | `ClawConstants.Agent`、`Tool`、`Session` | 注解与业务调用共享同一规则来源。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/skill/SkillConstants.java`：全部字段、isValidName | `ClawConstants.Skill` | 保持 Skill 的名称、ID、限制和文件约定完整归属。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtime/AgentRuntimeManager.java`：CAMPUSCLAW_DIRECTORY、AGENT_FILE、SETTINGS_FILE、SYSTEM_FILE | `ClawConstants.Runtime` | 目录生成、提示词读取和命令发现复用固定布局。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/RuntimeApiConstants.java`：BASE_PATH、MODEL_ID_PATTERN、MAX_MESSAGE_CHARACTERS、MAX_FILE_IDS | `ClawConstants.RuntimeApi` | 保留 HTTP 路径和标准 Bean Validation 约束；正则字符串改名 MODEL_ID_REGEX，并同处提供编译模式。 |
| `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/common/client/mate/MateCredentialHeaders.java`：全部字段；同目录 MateToolMeta.ALLOW、MateToolResponseException.ERROR_CODE | `ClawConstants.Mate` | 凭据转发、元数据权限值及稳定响应错误码集中定义。 |

删除四个旧常量/模式类；删除业务类中的已迁移字段，不提供转发别名。Mate 权限判定和缺省值使用
同一个 `TOOL_PERMISSION_ALLOW` 值，但仍由原调用方分别决定何时允许、何时应用默认值。
Home 与受管 Runtime 的 `.campusclaw` 分别表示用户级配置目录和 Agent 内部目录，保留独立成员以明确语义。

以下类别保留所属职责：枚举及类型化实例（如 ProviderId、NodeMetrics.EMPTY）、日志和客户端单例、
私有解析/算法细节（如 frontmatter 分隔符、分页游标加密参数、工具输出截断实现）、注入配置和配置默认值。
同值不等于同一规则；不把不同工具的独立限制合成一个全局数值，也不将部署参数固定化。

## 3. 目标架构与依赖

唯一手写定义文件为：

`modules/common/src/main/java/com/campusclaw/common/constant/ClawConstants.java`

包名为 `com.campusclaw.common.constant`，与 `com.campusclaw.ai`、`com.campusclaw.codingagent` 同级。
common 是独立 Maven 模块 `campusclaw-common`；生产源码仅使用 JDK 类型，不依赖业务模块。
ai 与 coding-agent-cli 是直接消费者，显式声明 common 依赖；agent-core 和 cron 沿现有依赖链获得
common，本次不为无直接引用的模块增加冗余依赖。所有分组均为同文件内的不可实例化静态类。

![共享常量所在模块的依赖关系](../module-architecture/module_dependencies.svg)

[PlantUML 源码](../module-architecture/diagram.puml#L1)

现有 `codingagent.common.client/dto/util` 服务侧代码继续由服务模块拥有；新增底层 common 不反向依赖
这些客户端、DTO 或 Spring 服务。根 POM 既有公共依赖继承机制不变。

## 4. 行为与边界

- Jakarta `@Pattern`、`@Size` 和 Spring 路由继续引用编译期常量，校验、异常映射和 HTTP 契约保持不变。
- Skill 仍使用唯一严格名称策略；ID 大小写、长度上限、文件字节限制及原有错误处理保持不变。
- 常量迁移不增加 I/O 或业务状态。Pattern 在对应分组初始化时编译一次并复用。
- 同步脚本将 common 加入 MODULES，生成 `campusclaw/src/main/java/com/huawei/hicampus/claw/common/constant/ClawConstants.java`；新增布局回归防止漏同步新模块。
- 新方案同步替代 AGENTS.md 的旧组织规则；决策见 [ADR-0051](../decisions/0051-centralize-shared-claw-constants.md)。

## 5. 验证

运行五模块 test-compile；聚焦验证 common 名称约束、用户目录优先级、Skill 发布和加载、Mate 出站
校验与权限缓存、请求头转发、Session ID、HTTP 路由与 Bean Validation。执行格式和 Checkstyle、
镜像布局回归及包名转换逐文件比对、PlantUML/SVG 与 Markdown 引用检查、git diff --check。
公司父 POM 缺失时只报告镜像编译未完成，不能用普通 Reactor 构建替代公司验证。

## 6. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| 1.0.0 | 2026-09-04 | 新增底层 common 模块，将共享常量集中于 ClawConstants 的领域分组。 |
