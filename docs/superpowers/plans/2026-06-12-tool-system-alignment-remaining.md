# Tool System Alignment Remaining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the remaining tool-system alignment milestones after M1: declarative process tools, MCP tools, refresh/reload integration, and final verification.

**Architecture:** Continue using `ToolCatalog` as the single production aggregation point. New tool kinds enter through `ToolSource` implementations and expose only `AgentTool` to the LLM/runtime; refresh uses copy-on-write catalog snapshots.

**Tech Stack:** Java 21, Spring Boot, SnakeYAML, JUnit 5, Reactor Netty/WebFlux, Maven.

---

### Task 1: Declarative Process Tools

**Files:**
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/DeclarativeToolSource.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolDeclaration.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolDeclarationLoader.java`
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ProcessAgentTool.java`
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/ToolSourceContext.java`
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/catalog/DefaultToolCatalog.java`
- Test: `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/tool/catalog/DeclarativeToolSourceTest.java`

- [ ] Write failing tests for YAML parsing, project tool discovery from `<cwd>/.campusclaw/tools`, process execution, timeout handling, and `REPLACE` metadata.
- [ ] Add a context-aware catalog refresh request carrying cwd.
- [ ] Implement the loader and process-backed `AgentTool`.
- [ ] Run `./mvnw -pl modules/coding-agent-cli -am test -Dtest=DeclarativeToolSourceTest -Dsurefire.failIfNoSpecifiedTests=false`.

### Task 2: MCP Tool Adapter

**Files:**
- Create: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/mcp/*`
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/settings/Settings.java`
- Test: `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/tool/mcp/McpToolSourceTest.java`
- Test: `modules/coding-agent-cli/src/test/java/com/campusclaw/codingagent/tool/mcp/McpAgentToolTest.java`

- [ ] Write failing tests for `tools/list` mapping, default `<server>__<tool>` naming, `tools/call` content mapping, error mapping, and untrusted raw-name replacement rejection.
- [ ] Implement a JSON-RPC client abstraction with test fake support.
- [ ] Implement stdio and simple HTTP transports.
- [ ] Implement `McpToolSource`, `McpAgentTool`, and content mapper.
- [ ] Run focused MCP tests.

### Task 3: Refresh, CLI, Server, Cron Wiring

**Files:**
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/cli/CampusClawCommand.java`
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/command/builtin/ReloadCommand.java`
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/mode/server/ServerMode.java`
- Modify: `modules/coding-agent-cli/src/main/java/com/campusclaw/codingagent/mode/server/SessionPool.java`
- Modify: cron executor path as needed.
- Test: focused tests near each modified class.

- [ ] Write failing tests for CLI cwd refresh, `/reload` refreshing catalog, server `POST /api/tools/reload`, and new sessions using refreshed snapshots.
- [ ] Wire `ToolRefreshRequest` through CLI and server construction.
- [ ] Keep existing session snapshots stable unless explicitly reloaded.
- [ ] Run focused CLI/server/reload tests.

### Task 4: Final Verification

**Files:**
- Modify docs as needed.

- [ ] Update design docs to describe declarative and MCP production paths.
- [ ] Run `./mvnw spotless:apply`.
- [ ] Run `./mvnw -pl modules/agent-core -am test -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Run `./mvnw -pl modules/coding-agent-cli -am test -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Audit `docs/designs/tool-system-alignment.md` requirement-by-requirement before marking the goal complete.
