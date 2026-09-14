# ADR-0108：取消开发新增行数限制

| 项目 | 内容 |
|---|---|
| Status | Accepted |
| Date | 2026-09-14 |
| Source baseline | `09b32d8ea4a4b43b34be302d8b2745bdc417c9a2` |
| Change class | 产品约束取消、CI 架构变化 |
| Supersedes | [ADR-0048](0048-limit-added-code-lines-per-pull-request.html) |

## Context

基线 `.github/workflows/ci.yml:17-24` 调用行数检查及其测试；
`scripts/check-commit-additions.sh` 的 `MAX_ADDED_LINES` 和 `check_pull_request`
将最终 PR 非文档新增行数限制为 2000。模块与生成镜像共享额度，设计还据此设置 850/1800 行预算。
固定额度可能拆散同一职责的实现与必要测试，用户要求取消单次开发新增行数限制。

## Decision

取消单次开发、提交、PR 及模块/镜像的新增行数预算，删除专用脚本、测试与 CI 步骤，
同步维护中的指引。按职责、依赖、风险与验收边界组织主题 PR；其余构建、测试、镜像和发布要求继续执行。
完整有效规则与证据见[开发规模与 CI 验证](../designs/commit-addition-limit.md)。

## 选项与理由

| 选项 | 收益 | 代价与结论 |
|---|---|---|
| 提高阈值或增设豁免 | 保留自动规模拦截 | 仍会形成任意切片边界，还需维护例外；不采用 |
| 停止 CI 调用但保留脚本和预算 | 改动少 | 本地任务仍可能按旧规则拆分，文档与执行不一致；不采用 |
| 删除门禁及配套预算 | 完整职责与测试可以共同交付，取消统计维护 | 评审需按实际风险判断范围；采用 |

## Consequences

开发不再因新增行数超过旧阈值而失败，也不需要拆提交获得额度。
大 PR 仍需清晰说明职责、影响及验证；不以取消规模门禁为由降低验收要求。
外部自动化若调用已删除脚本，需移除该调用。本变更没有新增依赖或运行时行为变化。

## Related

- [开发规模与 CI 验证](../designs/commit-addition-limit.md)
- [被取代的 ADR-0048](0048-limit-added-code-lines-per-pull-request.html)
- [更早已取代的 ADR-0047](0047-limit-added-code-lines-per-commit.html)
