# Runtime Events v2 权威历史与完整性

| 属性 | 值 |
|---|---|
| 版本 | 1.1.0 |
| 日期 | 2026-09-08 |
| 契约基线 | `pi-mono-java-design@2ee2a3211da68ad87b0d9cab353e691b00bdaebd` |
| 实现基线 | `pi-mono-java@69b8ced3` |
| 首版清理提交 | `45d7ece9` |
| 退休类型收紧提交 | `ab0f2637` |
| 当前范围 | 全新安装的权威公共事件、精确完整性门禁与整数分页；不包含旧版本升级或存量数据迁移 |

## Context

Events v2 要求 POST 的完整 SSE 帧与 GET 历史使用同一公共事件，并按服务端提交顺序从旧到新返回。
内部 `t_session_entries` 还承担模型上下文恢复，不能直接作为 HTTP 公共历史；一条内部 Entry 也可能投影为
多条公共事件或经明确判断不公开。因此，公共历史需要稳定存储和逐 Entry 的精确完整性结论。

当前产品是第一版，不存在受支持的旧安装和存量历史。设计基线中为既有数据提出的逐类映射与一次迁移要求
不适用于本次交付。这是产品范围约束。实现只提供全新安装 schema，不保留升级脚本、迁移审核表、迁移函数
或来源字段；已写入的当前版本数据仍必须通过完整性门禁，不能因为取消迁移而降低查询校验。

## 源码证据与实现边界

以下实现路径相对 `modules/coding-agent-cli/src/main/`。

| 分类 | 路径与符号 | 观察或决策 |
|---|---|---|
| 已实现 | `resources/db/gaussdb/install/session_schema.sql` · `t_session_events` | 全新安装时创建公共事件权威表；保存稳定 ID、提交顺序、当前分支锚点、UTC 毫秒时间和安全 payload |
| 已实现 | `resources/db/gaussdb/install/session_schema.sql` · `t_session_event_projection` | 只保存 `session_id`、`anchor_entry_id` 和精确 `event_count`；没有迁移来源字段 |
| 已实现 | `java/com/campusclaw/codingagent/runtimeapi/event/CommittedEventProjection.java#project` | 将已提交 DTO 严格投影为类型化只读 Response VO；GET 不重新翻译已保存文本 |
| 已实现 | `java/com/campusclaw/codingagent/runtimeapi/persistence/MyBatisRuntimeSessionRepository.java#appendEntryWithUsage` | 在同一 Spring 事务写 Entry、Usage、公共事件、完整性标记和共享序号 |
| 已实现 | `java/com/campusclaw/codingagent/runtimeapi/persistence/MyBatisRuntimeSessionRepository.java#findEventPage` | 在一个 `REPEATABLE_READ` 事务内固定当前分支、核验完整性并读取数字页 |
| 已实现 | `resources/mapper/session/RuntimeSessionMapper.xml#countUnmappedCurrentBranchEntries` | 未知或已退休类型、缺标记、数量不符和不允许的公开/私有数量均关闭失败；零事件标记不能重新放行退休类型 |
| 已删除 | `resources/db/gaussdb/upgrade/` | `45d7ece9` 删除升级 schema/data/verify、迁移回归与运行手册；当前首版不支持升级 |
| 待 Events 集成线统一接入 | `java/com/campusclaw/codingagent/runtimeapi/web/RuntimeEventController.java` | HTTP 切换须与全部公共写入点一同交付；本次首版范围清理不单独切换 Controller |
| 清理依据 | `RuntimeEventProjector`、`RuntimeTerminalEventFactory` 与 `e866db6b` | 该源码基线旧类只把八个旧名称用于瞬时 SSE，没有相应的 Entry 持久化写入者；最终 HTTP 分支删除旧投影链后也删除对应枚举 |

pi 基线 `5cd93f688aaab89dbb6dfa4aca535f21796ae185` 的
`packages/agent/src/agent-loop.ts#runAgentLoop` 和 `#prepareToolCall` 产生消息与工具生命周期通知，
但没有 CampusClaw 的 HTTP 公共历史表、当前分支分页或数据库完整性标记。这些部分属于 CampusClaw
架构变化。

## 权威事件与整数分页

![权威公共事件查询](query_pipeline.svg)

[PlantUML 源码](diagram.puml#L1)

`t_session_events` 保存一次稳定 `event_id`、UTC 毫秒 `created_at`、当前分支锚点和安全 JSON。
内部 `event_seq` 与 Entry、Record 共用 Session 序号，只用于提交顺序，不进入响应。
`CommittedEventProjection` 是 GET 与 POST 完整帧的唯一响应装配入口；Response VO 不嵌套 DTO。

查询 Service 将缺省 `page/limit` 归一为 1/50，限制 `limit` 为 1～200，并用精确乘法计算 offset。
Repository 在同一个读取事务中固定 active leaf、完整性判断和事件页，多取一条决定 `nextPage`。
合法超范围返回空 `events` 和 `null nextPage`，不查询 total，也不跨请求保存快照。

## 首版写入完整性

`t_session_event_projection` 为每个当前版本 Entry 保存精确公共事件数量。公开类型通常至少一条；
保留的内部压缩和结构辅助类型必须为零。`assistant.thinking.completed` 只有在写入时已取得可信公开摘要，
才写正数；已明确为私有的内容写零。`tool.execution.started` 必须映射完整 `agent.tool_call`，不能用
零掩盖缺失的参数或确认信息。未知内部类型即使存在标记也会失败。

`assistant.message.started`、`assistant.message.delta`、`assistant.thinking.started`、
`assistant.thinking.delta`、`tool.execution.delta`、`tool.execution.completed`、`stream.end` 和
`stream.error` 属于已退休的旧 SSE/Entry 协议名称。preview 不持久化，完整工具事件由当前内部类型承载，
流关闭也不是权威历史事件；因此这八种类型即使手工写入 `event_count=0` 也必须关闭失败。该规则不影响
`session.compaction.started`、`session.compaction.failed`、`leaf`、`branch_summary` 和 `label` 等仍登记的
零事件内部记录。

完整性标记与公共事件必须由当前运行时在同一事务产生。GET 对当前分支逐 Entry 校验以下条件：

- 每个 Entry 恰好有一条 marker；
- marker 的 `event_count` 与权威表实际锚定数量一致；
- 内部类型已登记，且零/正数符合该类型的公开规则；
- 一对多事件不能只写其中一部分后把 Entry 标成完整。

任一条件不成立时返回稳定 `EVENT_LIST_FAILED`，不返回半份历史，也不尝试从内部 Entry 临时补造公开
字段。该关闭失败规则用于发现当前版本写入缺陷、事务外写入或数据损坏，与旧版本迁移无关。

## 一致性、锁与回滚

运行时锁顺序为 Session 主行、当前 execution、Session sequence、Entry/Usage、公共事件、完整性标记，
控制层关联最后写入。组合 append 任一步失败都会回滚 Entry、Record、Usage、统计、事件、标记和序号。
写入前把公共时间统一截断到 UTC 毫秒并回填 DTO，保证 SSE 投影与数据库读回一致。

全新安装脚本会破坏性重建完整 Session schema，只能用于尚未承载业务数据的首版部署环境。既有安装升级
不在当前产品范围内，不能用安装脚本代替升级工具。

## 测试与验证

既有单元测试覆盖 1/50 默认值、1/200 边界、数字 `nextPage`、空页、乘法溢出、未知公共 payload 和稳定
错误。真实 openGauss 测试覆盖当前分支过滤、缺标记、数量不符、未知类型、八种退休类型即使有零事件
标记也失败、工具调用缺口，以及 Thinking 私有零事件、公开摘要一事件和缺标记三种结果。原子失败测试证明公共事件插入冲突时关联写入全部回滚，
并证明写入 DTO、数据库读回和公共投影使用同一个 UTC 毫秒时间。

提交 `45d7ece9` 删除全部升级脚本和迁移回归，移除 `mapping_source` 列、约束、Mapper 参数与镜像测试参数；
保留安装 schema 中的权威事件表、精确完整性表及运行时查询门禁。该提交没有新增 Maven 依赖。

## 决策与版本历史

见 [ADR-0078](../../decisions/0078-authoritative-event-history.html)、
[ADR-0092](../../decisions/0092-first-release-authoritative-event-history.html) 和
[ADR-0101](../../decisions/0101-reject-retired-event-entry-types.html)。

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.1.0 | 2026-09-08 | 删除八种退休 SSE/Entry 类型的零事件兼容白名单，手工标零仍关闭失败；保留当前 Compact 与结构辅助类型 |
| 1.0.0 | 2026-09-08 | 按首版产品范围移除存量迁移，保留权威事件、原子写入、完整性门禁与数字分页 |
| 0.1.1 | 2026-09-08 | 明确迁移独立交付边界，记录查询及运行角色权限验证 |
| 0.1.0 | 2026-09-08 | 记录权威公共事件、整数分页、精确完整性门禁和旧历史迁移候选方案 |
