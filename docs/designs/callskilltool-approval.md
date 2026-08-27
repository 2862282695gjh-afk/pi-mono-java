# callSkillTool 权限审批实现方案

> 在 `callSkillTool.execute()` 内部实现 skill 工具的 ask/allow/deny 审批。
> 权限跟着工具元数据走(`list_tools` 下发 `permission` 字段,callSkillTool 消费)。
> **不用 before hook,不用 ToolPermissionStore,不改 AgentLoop / ToolExecutionPipeline / AgentTool 接口。**

---

## 1. 为什么在 callSkillTool.execute 里(不在 before hook)

```
ToolExecutionPipeline.execute(callSkillTool):
  → applyBeforeHook                 ← 第 1 层:对 callSkillTool 本身(AgentTool 级)
  → invokeTool → callSkillTool.execute()
      → 查 ToolMeta.permission      ← 第 2 层:对 skill 工具(query/chart/export)
      → toolClient.callTool()       ← skill 工具是 call_tool 调的,不经过 Pipeline
```

skill 工具(query/chart)是 callSkillTool **内部** `call_tool` 调的,**不经过 ToolExecutionPipeline**。before hook 拦不到。所以 skill 工具的 ask 只能在 callSkillTool.execute 内部。

---

## 2. ToolMeta 加 permission 字段

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool;

import java.util.Map;

/**
 * Skill 工具元数据,由 ToolClient.list_tools 返回。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public record SkillToolMeta(
        String name,
        String description,
        Map<String, Object> inputScheme,
        Map<String, Object> outputScheme,
        boolean isConcurrencySafe,
        String permission            // "allow" / "ask" / "deny"(新增)
) {
    public static final String ALLOW = "allow";
    public static final String ASK   = "ask";
    public static final String DENY  = "deny";
}
```

list_tools 返回的 JSON(每个 skill 工具一条):

```json
{
  "name": "export",
  "description": "Export query results to file",
  "inputScheme": { "type": "object", "properties": { "path": { "type": "string" } } },
  "outputScheme": { "type": "string" },
  "isConcurrencySafe": false,
  "permission": "ask"
}
```

---

## 3. 用户审批接口 ApprovalUI

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool;

import java.util.Map;

/**
 * 用户审批 UI 抽象(TUI / RPC / Server 各自实现)。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public interface ApprovalUI {

    /**
     * 询问用户是否允许 skill 工具调用。
     *
     * @param skillName   skill 名
     * @param toolName    skill 工具名
     * @param args        工具参数
     * @param description 工具描述(给用户看)
     * @return true=allow, false=deny
     */
    boolean ask(String skillName, String toolName, Map<String, Object> args, String description);
}
```

TUI 实现(交互模式):

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 终端审批 UI(交互模式按 y/n)。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public class TerminalApprovalUI implements ApprovalUI {

    private static final Logger log = LoggerFactory.getLogger(TerminalApprovalUI.class);

    @Override
    public boolean ask(String skillName, String toolName, Map<String, Object> args, String description) {
        log.info("Approval required: {}.{}", skillName, toolName);
        System.out.println("\n[审批] skill 工具 " + skillName + "." + toolName);
        System.out.println("  说明: " + description);
        System.out.println("  参数: " + args);
        System.out.print("  允许执行?(y/n): ");
        try {
            int ch = System.in.read();
            boolean approved = (ch == 'y' || ch == 'Y');
            log.info("User {} skill tool: {}.{}", approved ? "approved" : "denied", skillName, toolName);
            return approved;
        } catch (Exception e) {
            log.warn("Approval read failed, defaulting to deny: {}.{}", skillName, toolName, e);
            return false;
        }
    }
}
```

非交互模式(OneShot / cron → 默认拒绝 ask 工具):

```java
public class NonInteractiveApprovalUI implements ApprovalUI {
    @Override
    public boolean ask(String skillName, String toolName, Map<String, Object> args, String description) {
        // 非交互模式不能问用户,默认拒绝 ask 工具(fail-closed)
        log.warn("Cannot ask user in non-interactive mode, denying: {}.{}", skillName, toolName);
        return false;
    }
}
```

RPC / Server 模式(转发客户端):

```java
public class RpcApprovalUI implements ApprovalUI {
    @Override
    public boolean ask(String skillName, String toolName, Map<String, Object> args, String description) {
        // 发审批请求给客户端,等客户端响应
        // return rpcClient.requestApproval(skillName, toolName, args, description);
        return false;  // 占位
    }
}
```

---

## 4. 核心:CallSkillTool(含权限审批)

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;

/**
 * callSkillTool:调用 skill 工具的元工具。
 * <p>
 * 模型 emit tool_use("callSkillTool", {skill, tool, args}) 触发。
 * execute 内部:
 * <ol>
 *   <li>查 ToolMeta.permission(从 list_tools 缓存的元数据)</li>
 *   <li>deny  → 直接拒绝</li>
 *   <li>ask   → ApprovalUI.ask() 用户审批</li>
 *   <li>allow → toolClient.callTool(skill, tool, args)</li>
 * </ol>
 * 不走 before hook(skill 工具不经过 ToolExecutionPipeline)。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public class CallSkillTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CallSkillTool.class);

    private final ToolClient toolClient;
    private final ApprovalUI approvalUI;

    /**
     * Skill 工具元数据缓存:key = "skillName.toolName",value = SkillToolMeta。
     * 由 listTool 工具调用时填充(或启动时 refresh)。
     */
    private final ConcurrentHashMap<String, SkillToolMeta> toolMetaCache = new ConcurrentHashMap<>();

    public CallSkillTool(ToolClient toolClient, ApprovalUI approvalUI) {
        this.toolClient = toolClient;
        this.approvalUI = approvalUI;
    }

    // ==================== AgentTool 接口 ====================

    @Override
    public String name() {
        return "callSkillTool";
    }

    @Override
    public String label() {
        return "Call Skill Tool";
    }

    @Override
    public String description() {
        return "Call a tool provided by a skill. Pass skill name, tool name, and args. "
                + "Use listTool first to discover available skill tools.";
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode parameters() {
        // JSON Schema(简化,生产用 ObjectMapper 构造)
        return JSON_SCHEMA;
    }

    private static final com.fasterxml.jackson.databind.JsonNode JSON_SCHEMA =
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();  // 占位,实际构造 schema

    // ==================== 核心:execute(含权限审批)====================

    @SuppressWarnings("unchecked")
    @Override
    public AgentToolResult execute(String toolCallId,
                                  Map<String, Object> args,
                                  CancellationToken signal,
                                  AgentToolUpdateCallback onUpdate) throws Exception {

        String skillName = (String) args.get("skill");
        String toolName  = (String) args.get("tool");
        Map<String, Object> skillArgs = (Map<String, Object>) args.get("args");

        if (skillName == null || toolName == null) {
            return AgentToolResult.error("Missing 'skill' or 'tool' parameter");
        }

        // ==================== 权限审批(在 callSkillTool 内部,不走 before hook)====================

        String cacheKey = skillName + "." + toolName;
        SkillToolMeta meta = toolMetaCache.get(cacheKey);
        String permission = (meta != null && meta.permission() != null)
                ? meta.permission()
                : SkillToolMeta.ALLOW;  // 默认 allow(无元数据时放行)

        log.info("callSkillTool: skill={} tool={} permission={}", skillName, toolName, permission);

        switch (permission) {

            case SkillToolMeta.DENY:
                // 直接拒绝(不调 call_tool)
                log.info("Tool denied by metadata: {}.{}", skillName, toolName);
                return AgentToolResult.error(
                        "Tool denied: " + skillName + "." + toolName);

            case SkillToolMeta.ASK:
                // 用户审批
                String desc = meta != null ? meta.description() : "Skill tool requires approval";
                log.info("Asking user approval: {}.{}", skillName, toolName);
                boolean approved = approvalUI.ask(skillName, toolName, skillArgs, desc);
                if (!approved) {
                    log.info("User denied: {}.{}", skillName, toolName);
                    return AgentToolResult.error(
                            "User denied: " + skillName + "." + toolName);
                }
                log.info("User approved: {}.{}", skillName, toolName);
                break;

            case SkillToolMeta.ALLOW:
            default:
                // 放行
                break;
        }

        // ==================== 调 skill 工具(call_tool)====================

        log.info("Calling skill tool: {}.{}", skillName, toolName);
        ToolClient.ToolResult result = toolClient.callTool(skillName, toolName, skillArgs);

        // 转 AgentToolResult
        return new AgentToolResult(
                result.content(),
                result.metadata(),
                result.isError());
    }

    // ==================== 元数据缓存管理(由 listTool 工具调用时更新)====================

    /**
     * 更新 skill 工具元数据缓存(listTool 调 list_tools 后更新)。
     */
    public void updateMeta(String skillName, java.util.List<SkillToolMeta> tools) {
        for (SkillToolMeta meta : tools) {
            toolMetaCache.put(skillName + "." + meta.name(), meta);
        }
        log.info("Updated skill tool meta cache: skill={} count={}", skillName, tools.size());
    }

    /**
     * 清除某 skill 的缓存。
     */
    public void clearMeta(String skillName) {
        toolMetaCache.keySet().removeIf(key -> key.startsWith(skillName + "."));
    }
}
```

---

## 5. ListTool(更新元数据缓存)

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;

/**
 * listTool:列出某 skill 的工具(名称/描述/参数/权限)。
 * 模型调 listTool 后,从返回结果知道有哪些 skill 工具、各自 permission(allow/ask/deny)。
 * 同时更新 CallSkillTool 的元数据缓存(供 callSkillTool 审批用)。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public class ListTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ListTool.class);

    private final ToolClient toolClient;
    private final CallSkillTool callSkillTool;   // 引用,用于更新缓存

    public ListTool(ToolClient toolClient, CallSkillTool callSkillTool) {
        this.toolClient = toolClient;
        this.callSkillTool = callSkillTool;
    }

    @Override
    public String name() {
        return "listTool";
    }

    @Override
    public String label() {
        return "List Skill Tools";
    }

    @Override
    public String description() {
        return "List available tools for a skill. Returns tool name, description, "
                + "input schema, and permission (allow/ask/deny).";
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode parameters() {
        return LIST_SCHEMA;
    }

    private static final com.fasterxml.jackson.databind.JsonNode LIST_SCHEMA =
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();  // 占位

    @Override
    public AgentToolResult execute(String toolCallId,
                                  Map<String, Object> args,
                                  CancellationToken signal,
                                  AgentToolUpdateCallback onUpdate) throws Exception {

        String skillName = (String) args.get("skill");
        if (skillName == null) {
            return AgentToolResult.error("Missing 'skill' parameter");
        }

        // 调 ToolClient.list_tools
        List<SkillToolMeta> tools = toolClient.listTools(skillName);

        // 更新 CallSkillTool 的元数据缓存(关键:让 callSkillTool 知道 permission)
        callSkillTool.updateMeta(skillName, tools);

        log.info("Listed skill tools: skill={} count={}", skillName, tools.size());

        // 返回工具列表给模型(名称/描述/权限)
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools for skill '").append(skillName).append("':\n");
        for (SkillToolMeta tool : tools) {
            sb.append("  - ").append(tool.name())
              .append(" (").append(tool.permission()).append("): ")
              .append(tool.description()).append("\n");
        }
        return new AgentToolResult(sb.toString(), null, false);
    }
}
```

---

## 6. ToolClient 接口

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 工具服务 client:调远程工具 server 的 list_tools / call_tool。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public interface ToolClient {

    /**
     * 列出某 skill 的工具(含 permission 元数据)。
     */
    List<SkillToolMeta> listTools(String skillName);

    /**
     * 调用某 skill 的工具。
     */
    ToolResult callTool(String skillName, String toolName, Map<String, Object> args);

    /**
     * 工具结果。
     */
    record ToolResult(String content, Map<String, Object> metadata, boolean isError) {}
}
```

---

## 7. 接入(在 mode 启动时)

```java
// 在 InteractiveMode / OneShotMode / RpcMode 启动逻辑里

// 1. 创建 ToolClient(实际传输:HTTP / stdio)
ToolClient toolClient = new HttpToolClient("http://tool-server:8888");

// 2. 创建 ApprovalUI(按模式)
ApprovalUI approvalUI = new TerminalApprovalUI();        // interactive
// ApprovalUI approvalUI = new NonInteractiveApprovalUI(); // one-shot / cron(默认拒绝 ask)
// ApprovalUI approvalUI = new RpcApprovalUI();             // rpc / server

// 3. 创建两个元工具(相互引用:listTool 更新 callSkillTool 的缓存)
CallSkillTool callSkillTool = new CallSkillTool(toolClient, approvalUI);
ListTool listTool = new ListTool(toolClient, callSkillTool);

// 4. 加进 agent 的工具集
agent.setTools(List.of(
    readTool, bashTool, editTool, grepTool, globTool, lsTool,  // 基础工具(不变)
    listTool,                                                     // 新增
    callSkillTool                                                 // 新增
));

// 5. 不用 setBeforeToolCall(skill 工具的 ask 在 callSkillTool 内部)
```

---

## 8. 完整流程

```
模型从 system prompt 知道有 skill(skillPromptFormatter / skill 描述)
  ↓
模型调 listTool(skill="data_query")
  → toolClient.listTools("data_query")
  → 返回工具列表 + 更新 callSkillTool.toolMetaCache
  → 模型看到:
      - query  (allow): 数据查询
      - chart  (allow): 画图
      - export (ask):   导出文件
      - delete (deny):  删除数据
  ↓
模型调 callSkillTool(skill="data_query", tool="query", args={...})
  → callSkillTool.execute:
      查 toolMetaCache["data_query.query"].permission = "allow"
      → toolClient.callTool("data_query", "query", args)
      → 返回结果
  ↓
模型调 callSkillTool(skill="data_query", tool="export", args={path:"/out.csv"})
  → callSkillTool.execute:
      查 toolMetaCache["data_query.export"].permission = "ask"
      → approvalUI.ask("data_query", "export", {path:"/out.csv"}, "导出文件")
      → 用户按 y
      → toolClient.callTool("data_query", "export", args)
      → 返回结果
  ↓
模型调 callSkillTool(skill="data_query", tool="delete", args={...})
  → callSkillTool.execute:
      查 toolMetaCache["data_query.delete"].permission = "deny"
      → 直接返回 error("Tool denied: data_query.delete")
      → 不调 call_tool
```

---

## 9. 不改的(确认)

| 组件 | 改不改 | 原因 |
|---|---|---|
| AgentLoop | **不改** | 不感知 callSkillTool 内部逻辑 |
| ToolExecutionPipeline | **不改** | callSkillTool 是普通 AgentTool,走正常 execute |
| AgentTool 接口 | **不改** | callSkillTool 实现 现有接口 |
| BeforeToolCallHandler | **不用** | skill 工具不经过 Pipeline,ask 在 execute 内部 |
| ToolPermissionStore | **不用** | permission 跟着 ToolMeta(list_tools 下发) |

---

## 10. 新增文件清单

```
coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/
├── SkillToolMeta.java            // record: name/description/inputScheme/outputScheme/isConcurrencySafe/permission
├── ApprovalUI.java               // 接口
├── TerminalApprovalUI.java       // TUI 实现(y/n)
├── NonInteractiveApprovalUI.java // 非交互(默认拒绝 ask)
├── CallSkillTool.java            // 核心:调 skill 工具 + 权限审批
├── ListTool.java                 // 列 skill 工具 + 更新元数据缓存
└── ToolClient.java               // 接口:list_tools / call_tool
```

---

## 11. 设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| ask 在哪实现 | callSkillTool.execute 内部 | skill 工具不经过 ToolExecutionPipeline,before hook 拦不到 |
| permission 来源 | ToolMeta.permission(list_tools 下发) | 声明式,server 声明,client 消费;和 isConcurrencySafe 同一套 |
| 不用 before hook | skill 工具不走 Pipeline | 只有 skill 工具需要 ask,基础工具不需要 |
| 不用 ToolPermissionStore | permission 在元数据里 | 不用单独规则存储,跟着 list_tools 走 |
| ApprovalUI 接口 | 多模式适配 | TUI=y/n,非交互=拒绝,rpc=转发 |
| 非交互默认拒绝 | fail-closed | 不能问用户时,拒绝 ask 工具(安全) |
