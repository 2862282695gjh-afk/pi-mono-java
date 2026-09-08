# Runtime Events v2 存量历史迁移（历史提案）

| 属性 | 值 |
|---|---|
| 状态 | 已被首版全新安装决策取代 |
| 版本 | 1.0.0 |
| 日期 | 2026-09-08 |
| 历史契约基线 | `pi-mono-java-design@2ee2a3211da68ad87b0d9cab353e691b00bdaebd` |
| 历史实现提交 | `247ee0af` |
| 取代提交 | `pi-mono-java@45d7ece9` |

## 历史背景

本目录曾记录一个面向已有安装的 Events v2 存量历史迁移候选方案。方案通过审核输入、严格 payload
校验、Session 原子批处理和可重跑 ID，把能证明安全的旧 Entry 转为公共事件，并对旧 Skill 正文、私有
Thinking、未知类型和缺失关联关闭失败。相关 SQL 曾在隔离 openGauss 数据库中完成回归验证。

用户随后明确当前产品是第一版，不存在受支持的旧安装或升级场景。继续交付升级脚本会制造并不存在的
运维入口和兼容承诺，因此该候选方案不进入首版产品。提交 `45d7ece9` 删除
`resources/db/gaussdb/upgrade/` 下的 schema、data、verify、回归脚本和运行手册，同时移除只为区分
runtime/migration 而存在的 `mapping_source` 字段。

## 当前边界

- 当前安装只使用 `resources/db/gaussdb/install/session_schema.sql` 建立全新 Session schema。
- `t_session_events` 和 `t_session_event_projection` 仍是首版权威历史与完整性门禁，未随迁移删除。
- 当前运行时在同一事务写内部 Entry、Usage、公共事件和精确 `event_count`；GET 对当前分支关闭失败。
- 本目录不再提供可执行 SQL、发布顺序、维护窗口、回滚步骤或 PlantUML 流程图，不能作为升级手册使用。
- 若未来产品首次提出保留既有数据的升级需求，必须基于当时 schema、契约和安全证据重新设计，不能恢复
  本历史提案后直接执行。

## 决策与版本历史

历史方案见 [ADR-0082](../../decisions/0082-restart-safe-event-history-migration.html)。当前决定见
[ADR-0092](../../decisions/0092-first-release-authoritative-event-history.html)。权威历史的现行设计见
[Runtime Events v2 权威历史与完整性](../runtime-event-history-v2/README.md)。

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0.0 | 2026-09-08 | 按首版产品范围将迁移方案转为历史记录，移除可执行步骤和图 |
| 0.1.1 | 2026-09-08 | 补充已有 projection 的精确数量、类型门禁与端到端缺口验证 |
| 0.1.0 | 2026-09-08 | 记录维护窗口、安全审核、Session 原子批处理、重跑与函数回滚边界 |
