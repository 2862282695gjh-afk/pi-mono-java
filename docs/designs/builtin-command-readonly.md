# Builtin Command 只读命令切片

> 版本：1.0.0 · 日期：2026-09-04 · 状态：Help / Status / Skills 已实现；未发布 Command HTTP 路由

## 1. Context

这是串行交付计划的第 2 个实现 PR。前置核心 PR #218 已合并，本分支从最新
`origin/main@6d7ba8483f69e8cccab04489a0111a27fde1315d` 创建，只注册三个有真实实现的
Builtin，不为剩余四个命令安装占位 Handler。七命令的 Help 集成测试使用四个测试专用定义补齐。

本记录只维护实现仓的交付证据，不变更 `pi-mono-java-design` 的任何文件或接口决策。
Skill 执行由同事并行开发，本 PR 的 Builtin Help 视图不代表全产品禁止 `skill:*`。
Name、Model、Thinking、Compact、应用边界及 HTTP 发布仍按已确认的后续切片交付。

## 2. 源码证据与关键定义

Java 基线为 `6d7ba8483f69e8cccab04489a0111a27fde1315d`；本切片实现为
`21d19792ce72546a7d0072501463dd4c1241b669`。下表 Java 路径的根为
`modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/`。

| 来源 | 相对根路径与符号 | 已观察行为 |
|---|---|---|
| Java 基线 | `runtimeapi/service/command/CompositeCommandRegistry.java:resolve`、`BuiltinCommandSource.java` | 来源过滤在解析之前；每次生成一个不可变 Catalog；启动聚合 Contributor 并查重 |
| Java 基线 | `runtimeapi/dto/command/CommandSessionSnapshotDTO.java:from` | 从已完成访问检查的持久化 Session DTO 复制状态、模型和 Thinking，不读取 Active Holder |
| Java 基线 | `runtime/AgentRuntimeManager.java:prepareCached/loadSnapshot/loadSkills` | 仅读取本地目录；完整性失败返回 null；完整无绑定目录返回空列表；此入口不调用 Mate |
| 本切片实现 | `runtimeapi/service/command/contributor/{Help,Status,Skills}CommandContributor.java:definition` | 各自提供元数据、只读准入与调用窄服务的 Handler lambda；没有中央名称分派 |
| 本切片实现 | `runtimeapi/service/command/readonly/CommandHelpFormatter.java:query` | 复用已有描述符，Help 只展示 Builtin，不再次调用 Registry |
| 本切片实现 | `runtimeapi/service/command/readonly/{RuntimeSessionStatusService,BoundSkillQueryService}.java:query` | 分别读取请求内持久化观察值和执行时完整绑定快照；返回内部 DTO |
| pi `4af9d21d3b4d664e4a29fcabfec85171077248e3` | `packages/coding-agent/src/core/agent-session.ts:1289` · `_tryExecuteExtensionCommand` | 扩展命令按名称查找并调用 `handler(args, ctx)`；不是 Java Builtin HTTP 实现 |
| 同一 pi 提交 | 同文件 `getSessionStats:3247`、`_bindExtensionCore:2464` | 前者聚合消息、用量和成本；后者把扩展、模板和 Skill 组合成命令元数据 |

pi 仅提供 Handler/上下文分离和资源元数据读取的行为参考。Java Status 不统计用量属于
已确认的产品约束；Java Skills 只返回名称、描述属于最小信息暴露约束；使用 Spring
Contributor、请求 Catalog 和内部 DTO 属于架构变化，不宣称这些类型已存在于 pi。
跨进程数据库与 HTTP 序列化验收仍是后续目标，不能把本 PR 的服务测试描述为该验收已通过。

## 3. 架构与数据流

![只读命令目标类结构](builtin-command-readonly/builtin_command_readonly.svg)

[PlantUML 源码](builtin-command-readonly/diagram.puml#L1)

下表包前缀为 `com.campusclaw.codingagent.runtimeapi`。

| 命令 | Spring 装配 `service.command.contributor` | 窄服务 `service.command.readonly` | 内部结果 `dto.command` |
|---|---|---|---|
| Help | HelpCommandContributor | CommandHelpFormatter | HelpCommandResultDTO |
| Status | StatusCommandContributor | RuntimeSessionStatusService | StatusCommandResultDTO |
| Skills | SkillsCommandContributor | BoundSkillQueryService | SkillsCommandResultDTO / SkillDTO |

Contributor 只依赖窄服务和核心 SPI，窄服务不反向依赖 Contributor。核心 SPI 保留在
`command.builtin` / `command.execution`，没有 Spring 依赖。DTO 不依赖窄服务。
所有 Spring 单例只保存固定协作者，Catalog、Session 观察值和结果均属于本次调用。

1. 后续应用层负责授权和读取持久化 Session，调用一次 Builtin 来源解析。
2. 本切片从 Catalog 取同一原始定义，Handler 使用 Context 中的 Catalog / Session。
3. Help 使用该 Catalog；Status 使用该 Session 观察值；Skills 调用一次 `prepareCached`。
4. 窄服务同步返回内部 DTO，Handler 用已完成的 CompletionStage 返回结果。
   服务错误可在取得 Stage 之前同步抛出，后续应用层须统一捕获同步异常与异步失败。
5. 后续应用层将 DTO 转为独立响应 VO，Controller 才返回 ResultBean；本 PR 不增加 VO 或路由。

## 4. 设计决策

沿用并补充 [ADR-0049：Builtin 分层与并行扩展边界](../decisions/0049-builtin-command-json-execution.html#readonly)。

- **Help 复用已有 Catalog**：不注入 Registry，防止重复解析和 Help → Source → Help 依赖环。
  空参数返回此请求已注册的 Builtin 描述符；非空参数 `strip()` 后精确查找，前导 `/`
  返回 `INVALID_COMMAND_REQUEST`，不匹配返回 `COMMAND_NOT_FOUND`。即使误传混合 Catalog，
  也不会把 Skill 定义当作 Builtin 帮助返回。
- **Status 使用持久化观察值**：不根据 Holder 推测状态、不补算 Token 或费用；同一请求
  内保持模型与 Thinking 的一致观察。下一请求重新读取最新 Session。
- **Skills 执行时读缓存，不刷新**：不在目录发现时读取 Agent；执行时缺少完整快照返回
  `AGENT_NOT_AVAILABLE`，完整空快照返回 `[]`。每次执行重新读取，不在单例中保存旧结果。
  不调用 SkillCommandSource，不暴露 Skill ID、版本、文件路径、正文或使用场景。
- **准入与参数规则分工**：三个命令在 idle / running 均允许只读查询；Help 为 OPTIONAL，
  Status / Skills 为 NONE。Catalog 的可用性是 Session 级只读准入预览，不保证本地 Agent
  快照存在；Skills 的完整性在执行时检查。NONE 的输入不可用原因仍是核心既有展示码。
- **DTO 是数据载体**：只含结果字段及列表防御复制，不在 DTO 中校验参数或业务状态。
  服务直接调用时也把 null 视为空参数；仅 Help 去除两端空白，Status / Skills 的空格、
  换行及其他非空参数均拒绝。统一请求长度、未知字段、Header 与名称格式在后续应用/HTTP 边界实现。
- **不提前发布公共接口**：三个内部结果不包含 `command` 或 `sourceEventSeq`；未来 VO
  组装器添加命令名，查询不返回领域事件序号。不把 DTO 的序列化测试当作 HTTP 契约测试。

## 5. 边界情况与性能（DFX）

- Help 的查找对大小写敏感，不接受缩写；空 Catalog 返回空列表。最终七个真实命令齐备后
  列表自然包含七项，不硬编码固定七项，不抛弃后续 Contributor。
- Status 可保留 null 模型值，不因 Agent 快照缺失导致查询失败。
- Skills 成功后若绑定刷新，下一次查询取得新快照；若目录随后不完整，不返回上一次列表。
  复用现有 Manager 完整性与路径安全校验，不建立独立的文件校验或刷新流程。
- Help 时间复杂度 O(n)，Status O(1)，Skills 复用现有完整快照文件读取成本并对 s 项排序
  O(s log s)。不占运行容量，不创建线程、不保存凭据，不写 Session、Entry 或通用命令事件。
- 没有新增日志记录参数、Skill 正文或 Mate Header。原 POST Events / SSE 未改动。

## 6. 契约改动与交付边界

只增加内部结果类型、三个 Contributor 和窄服务，补充 RuntimeErrorCode 的
`INVALID_COMMAND_REQUEST`（400）及 `COMMAND_NOT_FOUND`（404）和中英文错误文案。
复用 `AGENT_NOT_AVAILABLE`（422）。本 PR 不新增任何 HTTP 路由、鉴权入口、SQL 或持久化结构。
共享 HTTP 契约仍需在最终发布切片与 Skill 开发线对齐，不在此决定其命令执行请求形状。

## 7. 测试与验证

- 新增 28 项参数化展开后的测试：20 项 Contributor/Help/Status 测试与 8 项 Skills 测试。
  同时更新已有 Spring 组件扫描测试，使其实际发现三个真实 Contributor。
- Skills 使用真实 AgentRuntimeManager 和临时目录验证缺失、完整空列表、更新后的绑定及
  不完整目录；Mock Mate 在命令执行期间零调用。独立测试固定逆序输入，断言排序与最小字段。
- 模块与依赖 `./mvnw -q -pl modules/coding-agent-cli -am test`：1401 项，0 失败、0 错误、0 跳过。
- `./mvnw -q spotless:apply checkstyle:check`、`git diff --check` 通过。
- 指定质量脚本安装目录不存在；使用本机 `java-ut-coverage-loop.skill` 归档中同一原始脚本，
  检查两个新增测试文件及修改的核心测试文件：0 错误、0 警告。
- 默认 `scripts/sync-campusclaw.sh` 因公司 `NativeParent:26.0.0-SNAPSHOT` 无法解析而失败；
  显式 `--no-verify` 完成镜像同步，不宣称公司镜像已编译通过。
- 模块侧新增 748 行；完整 PR 门禁 `bash scripts/check-commit-additions.sh origin/main HEAD`
  为 1496/2000，低于 1800 软上限。下一个 Name PR 等本 PR 合并后从最新 main 创建。
- PlantUML ASCII、SVG 生成/XML/同步、文档链接/锚点、33 份 Markdown 无 Mermaid 检查通过；
  HTML 在禁用 JavaScript 的 1280px / 360px 宽度下渲染通过且无横向溢出，生成图已可视核对。

## 8. 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0.0 | 2026-09-04 | 记录 Help / Status / Skills 只读切片、DTO 边界、测试与串行 PR 行数预算；不改设计仓、不发布 HTTP。 |
