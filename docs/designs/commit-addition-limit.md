# CI 单提交新增代码行门禁设计

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 变更前源码基线 | `origin/main@c9d858bc8261bf07f5585f545b53495bf2226a56` |
| 实现分支 | `codex/ci-addition-limit` |
| 适用范围 | GitHub Actions、提交新增行检查脚本及其回归测试 |
| 变更类型 | 仓库 CI 架构变化 |
| 决策状态 | Accepted |

## 1. Context

变更前，`.github/workflows/ci.yml` 在 `pull_request` 和 `main` 分支 `push` 事件上执行 Spotless 与 Maven `verify`，但不限制单个提交的新增规模。大提交可以在已有格式化和构建门禁全部通过后进入评审，使改动难以逐提交理解、回滚和定位。

本次目标是在现有 CI 构建前增加单提交门禁：每个普通提交最多新增 2000 行非文档内容；文档不参与统计。该规则约束提交粒度，不限制整个 PR 的累计新增行数，也不修改 Java、HTTP、数据库或运行时契约。

## 2. 变更前源码证据

以下观察均来自变更前基线 `origin/main@c9d858bc8261bf07f5585f545b53495bf2226a56`：

| 观察到的行为 | 源码证据 |
|---|---|
| CI 由 `pull_request(main)` 和 `push(main)` 触发 | `.github/workflows/ci.yml:1-7`，`on` |
| Checkout 使用 `actions/checkout@v4` 的默认历史深度 | `.github/workflows/ci.yml:11`，`build.steps` |
| Checkout 后直接配置 JDK、执行 Spotless 和 Maven `verify` | `.github/workflows/ci.yml:13-25`，`build.steps` |
| 仓库没有对应的提交新增行检查脚本或测试 | `scripts/` 目录在该基线下仅包含 Claude hook、Git hook和公司镜像同步脚本 |

`scripts/check-commit-additions.sh`、`scripts/tests/check-commit-additions-test.sh` 及其行为均为本次 target-only 设计，不作为变更前已有能力描述。

## 3. 关键定义

- **单提交：** PR 分支相对目标分支共同祖先可达的每个非合并提交。合并提交不重复计算，其引入的非合并提交仍逐一检查。
- **新增代码行：** Git `numstat` 对非文档路径报告的文本新增行数。删除行不能抵扣新增行。
- **文档路径：** 任意 `docs`、`doc` 或 `documentation` 目录；Markdown、MDX、reStructuredText、AsciiDoc 文件；以及 README、CHANGELOG、CONTRIBUTING、LICENSE、NOTICE 标准文档文件。
- **通过边界：** 新增代码行小于或等于 2000 行；2001 行起失败。

## 4. 架构与数据流

![单提交新增代码行门禁流程](commit-addition-limit/commit_addition_limit.svg)

[PlantUML 源文件](commit-addition-limit/diagram.puml#L1)

CI 使用完整 Git 历史解析事件提供的 base/head SHA。检查脚本先求共同祖先，再按提交顺序读取 rename-aware `numstat`，过滤文档路径并累计新增行。任一提交越界时脚本输出提交 SHA、总数和逐文件新增行后返回非零；全部通过后才继续 JDK、Spotless 和 Maven 构建。

## 5. 设计决策

正式决策见 [ADR-0044：按普通提交限制非文档新增行](../decisions/0044-limit-added-code-lines-per-commit.html)。

### 5.1 按提交检查，不按 PR 汇总检查

规则目的是控制可评审、可回滚的提交单元。两个各新增 1500 行的提交分别合规，即使 PR 汇总为 3000 行；单个新增 2001 行的提交即使 PR 后续又删除内容，仍然失败。

### 5.2 使用 Git 原生 numstat

门禁统计版本库实际保存的提交差异，不依赖语言识别器或 GitHub API。`git diff-tree --numstat -M` 能区分新增与删除、识别纯重命名，并为文本文件提供稳定的新增行计数。二进制文件没有文本行数，不进入本门禁计数，仍由构建、资产规则和评审约束。

### 5.3 以路径和文档扩展名排除文档

排除规则集中在脚本函数内并由回归测试锁定。`docs` 等文档目录下的 HTML、SVG、PlantUML 或示例源码均作为文档排除；目录外的 HTML、YAML、JSON、测试和构建配置仍计入，避免把产品资产或工程配置误当文档。

### 5.4 合并提交不重复计数

Git 合并提交相对不同父提交没有唯一的新增行定义。门禁检查共同祖先到 head 的全部非合并提交，避免同步 `main` 时把目标分支已有改动重复记到合并提交，也保持仓库允许合并最新 `origin/main` 的工作流。

## 6. 边界情况

- 2000 行通过，2001 行失败；空提交和纯删除提交计为 0。
- 文档与代码在同一提交中出现时，只累计代码路径。
- 纯重命名通过 rename detection 保持 0 新增；重命名同时修改时只累计 Git 报告的新增行。
- base 不是 head 的祖先时，以二者共同祖先确定 PR 自有提交，支持目标分支在 PR 开发期间前进。
- 多个越界提交会在一次运行中分别报告，便于一次性修正。
- 无共同祖先、无效 SHA 或无法解析的 rename 记录属于门禁执行错误，返回退出码 2，不静默放行。

## 7. DFX

- **性能：** 复杂度与 PR 自有提交数及其变更文件数线性相关；完整 Checkout 增加历史拉取量，但避免依赖 GitHub API 和浅克隆补拉分支。
- **可维护性：** 统计规则位于独立脚本，CI 只负责传递事件 SHA；脚本可在本地复现。
- **可观测性：** 每个提交输出通过或失败摘要，失败时列出非文档文件新增行。
- **安全性：** 脚本只读取 Git 对象，不执行提交中的内容，不需要额外 Token 权限。
- **兼容性：** 使用 Bash 与 Git 标准命令，适配仓库支持的 macOS/Linux 和 GitHub Ubuntu Runner。

## 8. 契约改动

- PR 与 `main` push 的 `build` Job 新增 `Commit addition limit` 和脚本回归测试步骤。
- Checkout 改为 `fetch-depth: 0`，确保共同祖先与逐提交对象可用。
- 新增 CI 失败契约：任意普通提交新增非文档行超过 2000 时，构建步骤不再继续。
- 不修改应用 API、持久化、配置键、模块镜像或运行时行为。

## 9. 测试

`scripts/tests/check-commit-additions-test.sh` 使用临时 Git 仓库覆盖：

- 恰好 2000 行通过；
- 2001 行失败并报告 `2001/2000`；
- 大体量 `docs` 内容和文档扩展名不计入；
- PR 累计超过 2000、但每个提交均未越界时通过；
- 纯代码重命名不会被误记为全文件新增。

## 10. 验证

- 执行 `bash -n` 与 ShellCheck 校验两个脚本。
- 执行脚本回归测试。
- 对当前分支提交范围执行新增行门禁。
- 执行 `plantuml -tsvg docs/designs/commit-addition-limit/diagram.puml` 并验证 SVG XML。
- 校验 Markdown 不含 Mermaid、PlantUML 仅含 ASCII、文档链接和 PlantUML 行锚有效。
- 执行 `git diff --check` 和仓库 Maven `verify`。

## 11. 版本历史

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-03 | 新增每个普通提交最多 2000 行非文档新增内容的 CI 门禁设计 |
