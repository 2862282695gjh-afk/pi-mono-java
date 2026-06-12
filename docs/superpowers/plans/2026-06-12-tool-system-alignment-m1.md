# Tool System Alignment M1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the production ToolCatalog entry point while preserving current built-in tool behavior.

**Architecture:** `coding-agent-cli` gains a small catalog package that merges `AgentTool` contributions from Spring beans and the existing `ExtensionRegistry`. `CampusClawCommand` resolves `--tools` and `--no-tools` through `ToolSelection`, while `agent-core` adds backwards-compatible default methods for argument preparation and per-tool execution mode.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Mockito, Maven.

---

### Task 1: AgentTool Execution Alignment

**Files:**
- Modify: `modules/agent-core/src/main/java/com/campusclaw/agent/tool/AgentTool.java`
- Modify: `modules/agent-core/src/main/java/com/campusclaw/agent/tool/BeforeToolCallResult.java`
- Modify: `modules/agent-core/src/main/java/com/campusclaw/agent/tool/ToolExecutionPipeline.java`
- Test: `modules/agent-core/src/test/java/com/campusclaw/agent/tool/ToolExecutionPipelineTest.java`

- [ ] **Step 1: Write failing tests** for `prepareArguments`, `BeforeToolCallResult.argsOverride`, and `defaultExecutionMode` forcing sequential execution.
- [ ] **Step 2: Run** `./mvnw -pl modules/agent-core test -Dtest=ToolExecutionPipelineTest` and verify the new tests fail because the APIs/behavior do not exist.
- [ ] **Step 3: Add default methods** to `AgentTool`: `prepareArguments(Map<String,Object>)` returning the raw args and `defaultExecutionMode()` returning `ToolExecutionMode.PARALLEL`.
- [ ] **Step 4: Extend** `BeforeToolCallResult` with an immutable `argsOverride` map plus static helpers preserving `allow()` and `block(String)`.
- [ ] **Step 5: Update pipeline** so argument preparation runs before schema validation, before-hook overrides are used for execution/events/after-hook, and `executeAll(..., PARALLEL, ...)` switches to sequential when any call's tool asks for `SEQUENTIAL`.
- [ ] **Step 6: Re-run** `./mvnw -pl modules/agent-core test -Dtest=ToolExecutionPipelineTest`.

### Task 2: ToolCatalog Core

**Files:**
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolCatalog.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/DefaultToolCatalog.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolCatalogSnapshot.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolContribution.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolContributionSource.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolMergeStrategy.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolSelection.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolSource.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/SpringAgentToolSource.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ExtensionToolSource.java`
- Test: `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/tool/catalog/ToolCatalogTest.java`
- Test: `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/tool/catalog/ToolSelectionTest.java`

- [ ] **Step 1: Write failing tests** covering Spring source passthrough, extension source passthrough, priority ordering, duplicate `ADD` diagnostics, `REPLACE`, `DISABLE`, `--tools`, excludes, and `noTools`.
- [ ] **Step 2: Run** `./mvnw -pl modules/coding-agent-cli test -Dtest=ToolCatalogTest,ToolSelectionTest` and verify failures are missing classes.
- [ ] **Step 3: Implement records/enums/interfaces** with immutable lists/maps and diagnostic strings.
- [ ] **Step 4: Implement `DefaultToolCatalog`** with copy-on-write `refresh()`, `snapshot()`, and `resolve(ToolSelection)`.
- [ ] **Step 5: Implement Spring and extension sources** as Spring components where dependencies are available, with nullable/empty extension registry support.
- [ ] **Step 6: Re-run** the focused catalog tests.

### Task 3: CLI Production Wiring

**Files:**
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/cli/CampusClawCommand.java`
- Test: `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/cli/CampusClawCommandTest.java`

- [ ] **Step 1: Write failing CLI tests** proving `resolveEffectiveTools()` delegates to `ToolCatalog.resolve(ToolSelection)` for default, include-list, and no-tools cases.
- [ ] **Step 2: Run** `./mvnw -pl modules/coding-agent-cli test -Dtest=CampusClawCommandTest`.
- [ ] **Step 3: Inject `ToolCatalog`** into `CampusClawCommand`, keep existing constructor compatibility by creating a catalog from `List<AgentTool>` when Spring has not supplied one.
- [ ] **Step 4: Replace direct list filtering** with `ToolSelection.fromCli(toolsFilter, noTools)` and `toolCatalog.resolve(...)`.
- [ ] **Step 5: Re-run** the focused CLI tests.

### Task 4: Verification

**Files:**
- No production files.

- [ ] **Step 1: Run formatting** with `./mvnw spotless:apply`.
- [ ] **Step 2: Run agent-core tests** with `./mvnw -pl modules/agent-core test`.
- [ ] **Step 3: Run coding-agent-cli tests** with `./mvnw -pl modules/coding-agent-cli test`.
- [ ] **Step 4: Inspect diff** with `git diff --stat` and `git diff --check`.
