# Builtin Command 分层核心

> 版本：1.0.0 · 日期：2026-09-04 · 状态：核心切片已实现，尚无 Command HTTP 路由

## 1. Context

七个 Builtin 按独立 Contributor、Handler 和窄服务逐批交付。本 PR 只交付共用核心，
不注册占位命令，也不提前发布 GET/POST。Skill Command 由其他同事并行开发，
不是产品延期或禁用；Builtin 串行 PR 规则不限制 Skill 开发线。

原设计仓 PR #4 的“Skill 执行延期”表述由本次用户分工澄清取代。Builtin 过滤只是一个
来源视图，不是整个产品的命令白名单。最终共享 HTTP 命名、请求类型和发现清单必须与
Skill 开发线对齐后发布，不能把 Builtin 名称正则当作禁止 skill:* 的全局规则。

## 2. 源码证据与关键定义

| 基线 | 路径与符号 | 已观察行为 |
|---|---|---|
| Java 合并基线 a8192e047f4061bd385297d3dc6c4358362d0d49 | modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/runtimeapi/command/CompositeCommandRegistry.java · resolve | PR #210 已合并；聚合所有来源并返回只读描述符清单，尚无来源前置过滤 |
| 同一 Java 基线 | 同包 SkillCommandSource.java · list；ResolvedCommandDTO.java | Skill 从完整缓存生成发现 DTO，未提供执行 Handler；内部数据不是响应 VO |
| 本 PR 核心实现 8a402bc794f33e12c756b32b9b7274ad0c289b45 | 同包 BuiltinCommandSource.java · 构造器；BuiltinCommandDefinition.java · describe | 启动时聚合定义并查重；固定元数据、纯准入策略和 Handler 生成请求描述符 |
| 同一核心实现 | 同包 CompositeCommandRegistry.java · resolve；ResolvedCommandCatalog.java · findDefinition；CommandExecutionContext.java | 调用来源前过滤、描述符只解析一次、原定义身份与 Session 观察值固定在 Catalog |
| pi 4af9d21d3b4d664e4a29fcabfec85171077248e3 | packages/coding-agent/src/core/agent-session.ts:1289 · _tryExecuteExtensionCommand | 按名称解析命令并调用 handler(args, ctx)，属于本地扩展命令，不是 Java HTTP Builtin 的实现 |
| 设计输入 eec7baf1d52f9f982f7ab4836600cfa2386b3e7c（设计仓） | 04-命令与技能/00-Slash-Command通用模块/README.md；04-命令与技能/01-内置命令/README.md | 七个 Builtin、单次 Catalog 与串行九 PR 方案；Skill 分工以上述用户最新澄清为准 |

Java 新类型不能归因于合并前基线。pi 只提供 Handler 与上下文分离的行为参考；
Spring 来源装配、DTO/VO 和企业 HTTP 均为 Java 架构决策。

## 3. 架构与数据流

![Builtin 核心与并行扩展边界](builtin-command-core/builtin_command_core.svg)

[PlantUML 源码](builtin-command-core/diagram.puml#L1)

- 单例 BuiltinCommandSource 在构造时调用各 Contributor 一次，拒绝同名定义并复制集合。
  核心 PR 允许零 Contributor；后续七个具体命令逐个落地，不安装假 Handler。
- CommandDefinition 只定义 describe；DisplayCommandDefinition 包装现有 Skill 发现 DTO。
  接口不 sealed，独立 Skill 开发线可以增加真实可执行定义，而无需继承 Builtin。
- CommandDefinitionSource 新增 kind；默认 definitions 用展示定义适配现有 list。
  Builtin 覆盖 definitions 返回启动期定义。来源必须显式声明类型，不能默认假定 Skill。
- resolve(session, BUILTIN) 先检查来源类型，再解析所选来源；resolve(session) 保留全来源视图。
  来源声明与描述符 kind 不一致时失败，不接受伪装来源。
- Registry 每个定义只调用一次 describe，再将排序描述符与原定义索引一起固定。
  list/find 只读描述符；findDefinition 返回同一 Handler 所属的定义，不重新访问 Source。
- CommandExecutionContext 从 Catalog 读取同一 Session 快照及清单，并持有 Locale。
  不保存可变 RuntimeSessionDTO、Repository、Holder 或 Mate 凭据。

## 4. 设计决策

[ADR-0049](../decisions/0049-builtin-command-json-execution.html) 记录选项与取舍。

准入策略仅返回观察时的不可用原因，null 表示可用；带参数与无参数分别计算。
NONE 输入模式固定不可带参，原因是 COMMAND_ARGUMENTS_NOT_SUPPORTED。
它是展示原因，不是新增 HTTP 错误码。Handler/应用层仍负责参数校验；
Name、Model、Thinking、Compact 的权威写入必须在窄服务所属锁或事务内重新检查。

Handler 返回 CompletionStage&lt;? extends CommandResultDTO&gt;。未来应用层只把内部结果转换为
独立只读响应 VO，Web 再包装 ResultBean；核心没有 Controller 或结果类型分派。
本 PR 不承诺 CompletionStage 的取消隔离已实现，Compact 生命周期 PR 必须单独提供
不传播客户端取消的终态句柄。

固定元数据只读，列表均复制；placeholder 允许 null，保持已确认的无输入提示契约。
Controller 和七个具体 Handler/DTO/VO、数据库变更、Compact 生命周期均未在本 PR 发布。

## 5. 边界情况与 DFX

- 启动期同名 Contributor、请求期跨来源同名和类型不一致均直接失败。
- Skill 来源缺失、错误或开销不会影响只选择 Builtin 的请求；无刷新、模型或数据库调用。
- 调用方之后修改 RuntimeSessionDTO 不改变已解析清单；下次请求重新观察最新值。
- 七个命令的排序成本为 O(n log n)，定义精确查找使用只读 Map；不新增线程和持久化。
- Spring 单例只保存固定协作者；准入策略和 Handler 必须无请求字段，不能闭包捕获凭据。
- 不引入 commandId、通用生命周期存储或 Name 历史；既有消息 SSE 不变。

## 6. 并行开发衔接

| 所有者 | 本次交付 | 不代替另一条开发线决定 |
|---|---|---|
| Builtin 核心 PR | kind、definitions、固定 Catalog、Builtin Contributor/Handler/准入 | Skill 执行、参数、快照持久化与恢复 |
| Skill 开发线 | 在 CommandDefinitionSource 上声明 SKILL；按需覆盖 definitions | Builtin 的七个窄服务和 JSON 成功结果 |
| 最终 HTTP 集成 | 合并双方确认的命名、请求联合类型、清单与分派，补跨类型测试 | 不直接套用 Builtin-only 请求校验封锁 Skill |

旧 list(Session) 发现接口与无过滤 resolve(Session) 保留。下游实现 Source 时需补 kind()；
无需重写现有 Skill 读取或路径安全逻辑。

## 7. 测试与验证

- 新增八项核心测试：七名排序/来源隔离、Spring 重名失败/空核心启动、Skill 共存、
  单次解析与 Handler 身份、Session 复制、不可变集合及跨来源一致性。
- 模块及依赖测试：1343 项，0 失败、0 错误、0 跳过；最终参数/提示边界调整后针对性测试再次通过。
- Spotless、Checkstyle 与 git diff --check 通过。
- 指定质量脚本安装目录缺失，使用本机 java-ut-coverage-loop.skill 中同一原始脚本：
  0 errors；新测试 0 warnings，原有测试保留 4 条命名建议。
- sync-campusclaw.sh 正常验证入口因缺失 NativeParent:26.0.0-SNAPSHOT 失败；
  显式 --no-verify 同步镜像，不伪造公司 Parent，不声称公司镜像编译通过。
- 新增代码门禁：模块 621 行、镜像 621 行，合计 1242/2000；后续修改以最终门禁结果为准。
- PlantUML 生成、ASCII、SVG XML/同步及链接检查在发布前执行。

## 8. 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0.0 | 2026-09-04 | 核心切片、来源隔离、单次 Catalog 与 Skill 并行开发边界；未发布 HTTP。 |
