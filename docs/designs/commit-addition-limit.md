# CI PR 新增代码行门禁设计

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档版本 | v2.0 |
| 变更前源码基线 | `origin/main@2df1ffd0b15044221ea1facd43b0797f5cdbb098` |
| 评审实现提交 | `e7b68fd5a1b9c4699d30635d06b9467f3b8bdf2f` |
| 实现分支 | `codex/pr-addition-limit` |
| 适用范围 | GitHub Actions、PR 新增行检查脚本及其回归测试 |
| 变更类型 | 产品约束调整、仓库 CI 架构变化 |
| 决策状态 | Accepted |

## 1. Context

源码基线已经提供单提交新增行门禁：PR 内每个提交最多新增 2000 行非文档内容，多个分别合规的提交可以使整个 PR 超过 2000 行。该行为控制提交粒度，但不能满足“整个 PR 的新增代码不得超过 2000 行”的产品约束，也允许通过拆分提交绕过 PR 规模限制。

目标设计改为统计 PR 最终状态相对目标分支共同祖先的新增非文档代码行。门禁只度量最终送审差异，不累计中间提交已经删除或改写的瞬态内容；文档排除、文档移入代码目录的完整计费和 Git 读取失败时关闭门禁的规则保持不变。

## 2. 源码证据

以下观察来自变更前基线 `origin/main@2df1ffd0b15044221ea1facd43b0797f5cdbb098`：

| 观察到的行为 | 源码证据 |
|---|---|
| CI 在 PR 和 `main` push 上调用新增行脚本及其测试 | `.github/workflows/ci.yml:3-24`，`build.steps` |
| 门禁上限为 2000 行，文档路径与常见文档扩展名不参与统计 | `scripts/check-commit-additions.sh:6,18-38`，`MAX_ADDED_LINES`、`is_documentation_path` |
| 脚本枚举 merge-base 到 head 的每个提交并逐一比较上限 | `scripts/check-commit-additions.sh:96-163,172-202`，`check_commit` 及提交循环 |
| 普通提交、二父合并提交分别使用 `diff-tree`、`remerge-diff` 读取差异 | `scripts/check-commit-additions.sh:40-81`，`write_commit_stats` |
| 两个各新增 1500 行的提交被明确测试为通过 | `scripts/tests/check-commit-additions-test.sh:109-116`，`test_basic_limits` |

以下目标实现证据固定在评审实现提交 `e7b68fd5a1b9c4699d30635d06b9467f3b8bdf2f`：

| 目标行为 | 实现证据 |
|---|---|
| 脚本求 merge-base 后只对 merge-base 与 head 执行一次 rename-aware `git diff --numstat` | `scripts/check-commit-additions.sh:53-70,118-132`，`check_pull_request` |
| 所有非文档路径的最终新增行汇总后只比较一次 2000 行上限 | `scripts/check-commit-additions.sh:72-115`，`check_pull_request` |
| 文档移入代码目录时读取 head 中的目标 Blob 并按完整文本行数统计 | `scripts/check-commit-additions.sh:40-51,80-97`，`count_blob_lines` |
| 回归测试拒绝拆分到多个提交的 3000 行，并接受最终缩减为 1000 行的 PR | `scripts/tests/check-commit-additions-test.sh:109-133`，`test_basic_limits` |
| CI 步骤名称明确为 PR 新增行门禁 | `.github/workflows/ci.yml:17-24` |

本次从逐提交检查改为 PR 最终差异检查属于目标设计和产品约束调整，不是变更前已有行为。

## 3. 关键定义

- **PR 比较基线：** CI 提供的目标分支 base revision 与 PR head revision 的共同祖先，即 `git merge-base base head`。
- **PR 最终新增代码行：** Git 对比较基线与 head 最终树执行 `git diff --numstat -M` 后，所有非文档文本路径 additions 列的总和。deletions 列不抵扣 additions。
- **最终差异语义：** 中间提交新增、但在 head 前已经删除或改写掉的内容不计入；门禁约束评审者最终看到的 PR 差异，而不是历史编辑次数。
- **文档路径：** 任意 `docs`、`doc` 或 `documentation` 目录；Markdown、MDX、reStructuredText、AsciiDoc 文件；以及 README、CHANGELOG、CONTRIBUTING、LICENSE、NOTICE 标准文档文件。
- **跨边界重命名：** 旧路径属于文档、目标路径不属于文档时，按 head 中目标文件的完整文本行数计费；其他非文档重命名只累计 Git 报告的实际新增行。
- **通过边界：** PR 最终新增代码行小于或等于 2000 行；2001 行起失败。

## 4. 架构与数据流

![PR 新增代码行门禁流程](commit-addition-limit/commit_addition_limit.svg)

[PlantUML 源文件](commit-addition-limit/diagram.puml#L1)

CI Checkout 完整 Git 历史并传入 base/head SHA。脚本验证两个 revision、求共同祖先，然后将一次 rename-aware `git diff --numstat` 的结果写入临时文件。解析阶段保留重命名的旧、新路径，排除文档，并对文档移入代码目录的文件读取 head Blob 完整行数。所有计费路径汇总后只比较一次 2000 行上限；Git 读取或记录解析失败时返回执行错误，不把空输出解释为 0 行。

## 5. 设计决策

新决策见 [ADR-0048：限制整个 PR 的非文档新增代码行](../decisions/0048-limit-added-code-lines-per-pull-request.html)。原 [ADR-0047：限制每个提交的非文档新增行](../decisions/0047-limit-added-code-lines-per-commit.html) 已被取代。

### 5.1 按 PR 最终差异汇总，不按提交检查

2000 行是整个 PR 的评审规模上限。拆分提交不能增加额度：两个各新增 1500 行、最终合计新增 3000 行的提交必须失败。相反，中间曾新增 2500 行、在 head 前缩减为 1000 行时按最终 1000 行通过，因为评审面只保留 1000 行。

### 5.2 使用 merge-base 到 head 的单次 Git numstat

直接比较 base 与 head 会在目标分支前进而 PR 尚未同步时，把目标分支的新内容误当成 PR 反向差异。先求共同祖先，再比较共同祖先与 head，可稳定度量 PR 自有最终内容。单次最终树差异天然覆盖普通提交、合并提交、冲突解决和 merge-only 内容，不再需要枚举父提交或调用 `remerge-diff`。

### 5.3 保留文档排除和跨边界重命名防绕过

文档不消耗代码新增行额度。rename 记录同时保留旧、新路径：从文档路径移入非文档路径时，Git 可能报告 `0/0` 纯重命名，因此必须读取 head 的目标 Blob 并按完整文本行数计费。code-to-code 纯重命名保持 0 新增。

### 5.4 所有 Git 数据读取显式 fail-closed

`git diff` 先在 `if ! ...; then` 中执行并写入临时文件，再由循环解析，不能依赖 process substitution 或 `set -e` 传播 producer 失败。revision、merge-base、目标 Blob和 numstat 记录任一不可读或不可解析时返回执行错误 2。

## 6. 边界情况

- 2000 行通过，2001 行失败；纯删除的 additions 为 0。
- 多个提交的最终新增行合并计费，不能通过拆分提交获得多个 2000 行额度。
- 中间提交曾存在、但最终 head 已删除的内容不计入最终 PR 差异。
- 文档与代码同时变化时只累计代码路径；二进制文件没有文本行数，不进入本门禁。
- code-to-code 纯重命名计为 0；docs-to-code 重命名按 head 中目标文件完整文本行数计费。
- 主题分支合并目标分支时，以共同祖先排除目标历史；冲突解决和 merge-only 新内容只要存在于最终 PR 树，就进入最终差异。
- base 与 head 相同则新增 0 行；无共同祖先、无效 SHA、Git 对象不可读或 numstat 记录无法解析时失败关闭。

## 7. DFX

- **性能：** 复杂度与 PR 最终变更文件和文本行数线性相关；不再逐提交读取父信息或重建合并，开销低于旧算法。
- **可维护性：** 统计规则仍位于独立脚本，CI 只传递事件 SHA；保留原脚本路径以减少调用方迁移成本。
- **可观测性：** 输出 PR 总新增行与 2000 行上限；失败时列出每个计费文件的新增行。
- **安全性：** 脚本只读取 Git 对象，不执行 PR 内容；关键数据读取失败时关闭门禁。
- **兼容性：** 使用 Bash 与 Git 标准命令，适配仓库支持的 macOS/Linux 和 GitHub Ubuntu Runner。

## 8. 契约改动

- `scripts/check-commit-additions.sh` 的两个 revision 参数保持不变，但结果语义从“逐提交分别检查”改为“最终 PR 差异汇总检查”。
- PR 与 `main` push 的 CI 步骤显示名改为 `Pull request addition limit`；push 事件仍以 before/after 范围执行相同的最终差异检查，作为合并后的防御性复核。
- 新增 CI 失败契约：最终 PR 新增非文档代码超过 2000 行时停止后续构建；Git 统计无法完成时同样失败。
- 不修改应用 API、持久化、配置键、模块镜像或运行时行为。

## 9. 测试

`scripts/tests/check-commit-additions-test.sh` 使用临时 Git 仓库覆盖：

- 恰好 2000 行通过，2001 行失败；
- 两个提交分别新增 1500 行、最终合计 3000 行时失败；
- 中间新增 2500 行、最终缩减到 1000 行时通过；
- 大体量文档和文档扩展名不计入；
- code-to-code 纯重命名保持 0，docs-to-code 纯重命名及同时修改按目标完整内容计费；
- 合并目标分支不重复计费，merge-only 新文件和超大冲突解决进入最终差异；
- PR head Tree 对象不可读时返回执行错误 2，不能以 0 行假绿。

## 10. 验证

- 执行 `bash -n` 与 ShellCheck 校验两个脚本。
- 执行脚本回归测试。
- 对 `origin/main...HEAD` 执行 PR 新增行门禁。
- 执行 `plantuml -tsvg docs/designs/commit-addition-limit/diagram.puml` 并验证 SVG XML。
- 校验 Markdown 不含 Mermaid、PlantUML 仅含 ASCII、文档链接和 PlantUML 行锚有效。
- 执行 `git diff --check` 和仓库 Maven `verify`。

## 11. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v2.0 | 2026-09-03 | 将 2000 行限制从每个提交调整为整个 PR 的最终新增非文档代码行总量，并以 ADR-0048 取代 ADR-0047 |
| v1.1 | 2026-09-03 | 修复 Git 读取假绿、docs-to-code 重命名与 merge-only 绕过，固定评审实现提交，并将冲突 ADR 编号调整为 0047 |
| v1.0 | 2026-09-03 | 新增每个普通提交最多 2000 行非文档新增内容的 CI 门禁设计 |
