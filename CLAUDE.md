# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

CampusClaw (`com.campusclaw`, previously `pi-mono-java`) is a ToB Agent Runtime service built on JDK 21 + Spring Boot 3.4.5. It is a Maven multi-module project. The historical directory `modules/coding-agent-cli` now contains the Spring Boot service entry point and produces `campusclaw-agent.jar`; there is no CLI/TUI/RPC product mode.

## Build & Run

CampusClaw local launch and installation support **macOS and Linux only**. Do not add or maintain Windows-specific batch, PowerShell, `mvnw.cmd`, or Task Scheduler launch and installation entry points unless the platform policy is explicitly changed. The POSIX `mvnw` may retain upstream Cygwin/MinGW compatibility for best-effort Windows builds, without a Windows support or validation commitment.

The project requires **JDK 21** (not 17, not 25). `./mvnw` uses whatever `JAVA_HOME` is set. The mirror sync script auto-detects JDK 21 via Homebrew, `/usr/libexec/java_home -v 21`, SDKMAN, or common Linux paths.

| Command | Purpose |
|---|---|
| `./mvnw package -pl :campusclaw-coding-agent -am -DskipTests` | Build the service JAR only |
| `java -jar modules/coding-agent-cli/target/campusclaw-agent.jar` | Start the Spring Boot HTTP service |
| `./mvnw -pl :campusclaw-coding-agent -am spring-boot:run` | Development mode via the Spring Boot Maven plugin |
| `./mvnw test` | Run all tests |
| `./mvnw -pl modules/agent-core test -Dtest=AgentLoopTest` | Run a single test class (surefire) |
| `./mvnw -pl modules/agent-core test -Dtest=AgentLoopTest#name` | Run a single test method |
| `./mvnw spotless:apply` | Format code (import order `java,javax,com,org,*`, removes unused imports) |
| `./mvnw verify` | Full build incl. tests |

Notes:
- Surefire runs with `-XX:+EnableDynamicAgentLoading` (configured in root POM) — Mockito needs it on JDK 21.
- The canonical `application.yml` lives at `modules/coding-agent-cli/src/main/resources/application.yml`. Editing it requires rebuilding or restarting the service. There used to be a second copy at the repo root; do not reintroduce it.

## Architecture

Module dependency graph (from `docs/module-architecture.md`):

Direct dependencies: ai → common; agent-core → ai; cron → agent-core;
coding-agent-cli → common, ai, agent-core, cron. Common has no business-module dependency.

| Module | Artifact | Role |
|---|---|---|
| `modules/common` | `campusclaw-common` | Shared business constants in `com.campusclaw.common.constant.ClawConstants`, grouped by domain. |
| `modules/ai` | `campusclaw-ai` | Unified LLM abstraction. Providers (Anthropic, OpenAI, Google GenAI/Vertex, Bedrock, Mistral, and ~18 OpenAI-compatible flavors) live under `provider/`; types under `types/`; model registry under `model/`. |
| `modules/agent-core` | `campusclaw-agent-core` | Agent runtime. `Agent` is the façade; `AgentLoop` drives the LLM↔tool cycle; `ToolExecutionPipeline` runs tools with before/after hooks and JSON-schema validation; sealed `AgentEvent` hierarchy emits state transitions. |
| `modules/cron` | `campusclaw-cron` | JobRunr-backed scheduled agent runs, exposed as an `AgentTool` for agents to self-schedule. |
| `modules/coding-agent-cli` | `campusclaw-coding-agent` | Spring Boot HTTP/SSE service, managed Agent directory, common Session factory, Skill loader, and the closed eight-tool assembly. The directory name is historical. |

Key runtime concepts:
- **Startup model**: `java -jar` starts the Spring Boot MVC HTTP+SSE service. There is no alternate CLI Spring context or mode dispatcher.
- **Tool management**: `BuiltInToolName` closes the model-visible set to `Read`, `Find`, `Grep`, `Ls`, `Cron`, `ListMateTools`, `CallMateTool`, and `Agent`. Runtime, Cron, and Child profiles are strict. `ToolExecutionMode` controls only sequential versus parallel scheduling.
- **Extensibility**: `PackageManager` and local Skill installation/import are removed. Managed Skill metadata is read only from the Agent runtime directory prepared by `AgentRuntimeManager`; Mate tools are discovered in real time and invoked by name.
- **Session assembly**: Runtime HTTP, Cron trigger, and Child Execution all use `AgentSessionFactory`; Host persistence and lifecycle remain outside the common Session.
- **Reactive stack**: `ai` and `agent-core` use Reactor `Mono/Flux` throughout for streaming LLM responses. Don't `.block()` on the event stream path.

## Conventions to preserve

- Shared business constants belong in the bottom-level common module's `ClawConstants`, using nested domain groups. Keep regex strings and compiled patterns together, remove old aliases, and retain private implementation details and injected configuration in their owning code.

- Java 21 features are in active use (records, sealed interfaces, pattern matching) — don't downgrade.
- Spotless is enforced via `spotless-maven-plugin` with **palantirJavaFormat 2.66.0**; run the relevant module formatting checks for Java changes before committing. **Requires JDK 21** (palantir 不兼容 JDK 25 的 javac 内部 API)。
- Tests use JUnit 5 + Mockito + OkHttp `MockWebServer` (for provider integration tests).
- Managed runtime data lives under `agent/{agentId}/.campusclaw/` by default. Model credentials come from deployment configuration and are not persisted in Agent directories.

## Java conventions

For Java edits, consult the applicable sections of
[Java conventions and build checks](docs/agent-guides/java-conventions.md).
The guide retains copyright, logging, charset, exception, test-quality,
Javadoc and complexity requirements. It is not required reading for unrelated
Markdown or agent-configuration edits. The repository's `AGENTS.md` owns the
current constant organization, validation and publishing boundaries.

## CampusClaw corporate mirror

`campusclaw/` is a single-module corporate mirror of `modules/*`. Package is rewritten `com.campusclaw` → `com.huawei.hicampus.claw`. The mirror is **generated** — make changes in `modules/*`, then sync. It is outside the root Reactor and resolves `com.huawei.hicampus:NativeParent:26.0.0-SNAPSHOT` from the configured company Maven repository. Its project GAV is `com.huawei.campus:claw:1.0-SNAPSHOT`, and Maven's default artifact is `campusclaw/target/claw-1.0-SNAPSHOT.jar`. Because it does not inherit the root POM, its POM explicitly declares `slf4j-api` and `spring-boot-starter-log4j2`; `NativeParent` supplies their company-managed versions. Its hand-maintained `application.properties` defaults `campusmate.base-url` to `https://localhost:8591`; `CAMPUSMATE_BASE_URL` overrides that value.

| Command | Purpose |
|---|---|
| `./scripts/sync-campusclaw.sh` | Stage from `modules/*`, apply to `campusclaw/`, run `mvn compile` to verify |
| `./scripts/sync-campusclaw.sh --dry-run` | Show what apply would change without writing |
| `./scripts/sync-campusclaw.sh --no-apply` | Only stage to `build/campusclaw/`; leave `campusclaw/` untouched |
| `./scripts/sync-campusclaw.sh --no-verify` | Explicitly skip company-parent resolution and mirror compile (ordinary local environments only) |

Phases:
1. **Stage** — copy `modules/{common,ai,agent-core,cron,coding-agent-cli}` into `build/campusclaw/`, rewriting the package in `.java/.yml/.properties/.imports/...`. Remove GaussDB files from staged classpath resources and assemble `build/campusclaw/scripts/install/initdb_gaussdbv5.sql` from the corporate header template plus the table DDL in canonical `session_schema.sql`.
2. **Apply** — `rsync --delete` from `build/` to in-tree `campusclaw/`. Paths listed in `scripts/sync-campusclaw-exclude.txt` are preserved (corporate-mirror-only files that have no counterpart in `modules/*`). The generated `campusclaw/scripts/install/` directory contains only `initdb_gaussdbv5.sql`, and the legacy `campusclaw/src/main/resources/db/gaussdb/` directory is removed.
3. **Verify** — resolve `NativeParent` and compile `campusclaw/` with the sync script's auto-detected JDK 21. Failure to resolve the company parent is fatal; the script never silently skips this gate.

When adding a new file directly under `campusclaw/` that has no counterpart in `modules/*`, append its path to `scripts/sync-campusclaw-exclude.txt`, otherwise the next `--delete` will remove it. The current exclusions protect the corporate Skill tree and `CampusMateConfigurationTest`. The hand-tuned `application.properties` is environment-specific, contains no Actuator-specific overrides for the standalone service, and is never touched by the script; only `META-INF/spring/*.imports` propagate from `modules/*`.

The module-side GaussDB release files remain under `modules/coding-agent-cli/src/main/resources/db/gaussdb/` for standalone development. The corporate mirror publishes only `campusclaw/scripts/install/initdb_gaussdbv5.sql`, assembled from `scripts/templates/initdb_gaussdbv5-header.sql` and the table DDL in `install/session_schema.sql`; it does not publish the empty initial-data script, privilege placeholders, or upgrade README. The generated company script must start with the exact corporate database/schema/owner/grant header, must not contain `BEGIN` or `COMMIT`, and must place the matching `DROP TABLE IF EXISTS` immediately before every `CREATE TABLE`. `campusclaw/scripts/install/` is generated and must not contain hand-maintained files. Because the corporate SQL is an external install artifact rather than a classpath resource, `--skip-resources` does not skip it.

### pre-push guard

`scripts/git-hooks/pre-push` blocks `git push` whenever the push range touches `modules/` or `campusclaw/` and the mirror is out of sync. Activate it once per clone:

```bash
git config core.hooksPath scripts/git-hooks
```

The hook runs the sync script in dry-run + no-verify mode and parses rsync's `--itemize-changes` output together with managed asset update/deletion markers. Pushes that don't touch `modules/`, `campusclaw/`, or `scripts/sync-campusclaw*` skip the check. Do not bypass this guard; follow the publishing rules in `AGENTS.md`.

## Git workflow

`AGENTS.md` is the publishing source of truth: latest `origin/main`, one
`codex/<topic>` branch, explicit staging, and a verified Draft PR targeting
`main`. Never use default-branch commits, force-push or hook bypass as a
shortcut. Merge updated `origin/main` into the topic branch when needed.

Commits use Conventional Commits with Chinese descriptions:
`type(scope): 中文描述`. The repository merges PRs with merge commits; merging
or marking a Draft ready requires the user's explicit request. Personal agent
settings outside the repository's tracked content are local configuration,
not material to add to a CampusClaw PR.

## 决策记录与设计文档约定

涉及特性实现、模块契约或架构决策时，按下面的约定更新设计文档及适用的 ADR。拼写、纯格式、提示词整理，以及不改变设计的局部修复，不需要额外创建两份设计产物。已有设计受到影响时仍须同步更新。

### 设计文档 — `docs/designs/`
- 每个特性或模块在 `docs/designs/` 下创建或更新一份 markdown 设计文档。
- 采用 gstack `/plan-eng-review` 工程评审结构：Context（为什么）/ 关键定义 / 架构与数据流 / 设计决策（链接到对应 ADR）/ 边界情况 / 性能(DFX) / 契约改动 / 测试 / 验证。
- 文件名：模块级用 `<module>.md`；特性级用 `<feature-slug>.md`。

### 决策记录（ADR）— `docs/decisions/`
- 每个设计/架构决策记录为一个**自包含 HTML**：`docs/decisions/NNNN-<slug>.html`（`NNNN` 四位零填充、全局递增、不复用）。
- 单文件可直接浏览器打开、内联 CSS，风格对齐已有 ADR（如 `docs/decisions/0001-list-models-usable-credentials.html`）。
- 必含字段：Status（Proposed/Accepted/Superseded）、Date、Context、Decision、考虑过的选项及取舍（Pro/Con）、所选方案与理由、Consequences（正/负/后续）、Related（链接设计文档与关联 ADR）。
- 决策被推翻：新建 ADR 并把旧 ADR Status 改 Superseded，双向链接。

### 联动
- 设计文档「设计决策」小节逐条链接到对应 `docs/decisions/*.html`。
- `docs/` 与 `CLAUDE.md` 不在 campusclaw 镜像范围，无需 sync。

## Reference

- `README.md` — service quickstart, managed tools, runtime directories, and supported providers.
- `docs/module-architecture.md` — authoritative module/package breakdown.
- `docs/designs/sandbox-cleanup.md` — local Sandbox removal, MateService tool migration, and deletion record.
- `docs/designs/mate-tool-client.md` — MateService tool metadata and invocation client design.
- `docs/plans/campusclaw-http-v1-implementation.md` — Runtime V1 implementation mapping, source baseline, and validation evidence. The field-level contract is maintained as the interactive HTML document in the dedicated design repository; this repository intentionally has no OpenAPI or public WebSocket contract copy.
- `modules/*/`+`*-design.md` — per-module design docs (Story/AR format).
- `docs/designs/*.md` — feature/module design docs (gstack `/plan-eng-review` format); see "决策记录与设计文档约定".
- `docs/decisions/*.html` — ADR decision records (one self-contained HTML per decision).
- `scripts/sync-campusclaw.sh` + `scripts/sync-campusclaw-exclude.txt` — sync `modules/*` → `campusclaw/` (see "CampusClaw corporate mirror" section).
