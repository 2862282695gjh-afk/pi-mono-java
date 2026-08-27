# pi-mono-java allow/deny 权限审批实现方案

> 基于 pi-mono-java 现有的 `BeforeToolCallHandler` 扩展点,实现工具调用的 allow/deny 用户审批。
> **不改 AgentLoop / ToolExecutionPipeline / AgentTool 接口**,只新增审批逻辑 + 注入 hook。

---

## 1. 现状(pi-mono-java 已有的 hook 基础设施)

pi-mono 的 `ToolExecutionPipeline.execute()` 已内置 before/after hook:

```
execute(tool, call, args, ...):
  1. applyBeforeHook     ← BeforeToolCallHandler(可 block 拒绝)
  2. ToolExecutionStartEvent
  3. validateArguments(JSON Schema 校验)
  4. tool.execute(...)    ← 真正执行
  5. applyAfterHook      ← AfterToolCallHandler(可改写结果)
  6. ToolExecutionEndEvent
```

关键接口(已存在,不用改):

```java
// tool/BeforeToolCallHandler.java
public interface BeforeToolCallHandler {
    BeforeToolCallResult handle(BeforeToolCallContext context) throws Exception;
}

// tool/BeforeToolCallResult.java
public record BeforeToolCallResult(boolean block, String reason) {
    public static BeforeToolCallResult allow() { return new BeforeToolCallResult(false, null); }
    public static BeforeToolCallResult block(String reason) { return new BeforeToolCallResult(true, reason); }
}

// tool/BeforeToolCallContext.java(含 toolCall / validatedArgs / context)
```

注入方式(已存在):

```java
// Agent.java:159
agent.setBeforeToolCall(handler);  // 转发给 toolPipeline.setBeforeToolCall
```

---

## 2. 要实现的组件

### 2.1 权限规则存储 `ToolPermissionRule`

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool.permission;

import java.util.List;
import java.util.Map;

/**
 * 权限规则:按工具名 + 参数匹配,决定 allow / deny / ask。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public class ToolPermissionRule {

    public enum Effect { ALLOW, DENY, ASK }

    private final String toolName;          // 工具名(支持通配 *)
    private final Effect effect;            // allow / deny / ask
    private final String pathPattern;       // 可选:路径匹配(如 /sys/** 禁止)
    private final String description;       // 规则说明

    // 构造 / getter 省略

    /**
     * 检查当前工具调用是否匹配此规则。
     */
    public boolean matches(String toolName, Map<String, Object> args) {
        if (!matchToolName(this.toolName, toolName)) {
            return false;
        }
        if (pathPattern != null) {
            String path = extractPath(args);
            if (path == null || !matchPath(pathPattern, path)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchToolName(String pattern, String name) {
        return "*".equals(pattern) || pattern.equals(name);
    }

    private static String extractPath(Map<String, Object> args) {
        Object p = args.get("file_path");
        if (p == null) { p = args.get("path"); }
        if (p == null) { p = args.get("command"); }
        return p != null ? p.toString() : null;
    }

    private static boolean matchPath(String pattern, String path) {
        // 简单实现:前缀匹配(生产可换 Ant/Glob)
        if (pattern.endsWith("/**")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 3));
        }
        return pattern.equals(path);
    }
}
```

### 2.2 规则注册表 `ToolPermissionStore`

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 权限规则存储:管理 allow / deny / ask 规则列表。
 * 规则按添加顺序匹配,第一个命中的生效。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public class ToolPermissionStore {

    private final CopyOnWriteArrayList<ToolPermissionRule> rules = new CopyOnWriteArrayList<>();

    /**
     * 添加规则(后加的优先级低,先匹配)。
     */
    public void addRule(ToolPermissionRule rule) {
        rules.addIfAbsent(rule);
    }

    /**
     * 查找第一个匹配的规则。
     *
     * @return 匹配的规则,或 null(无规则 = 默认放行)
     */
    public ToolPermissionRule match(String toolName, Map<String, Object> args) {
        for (ToolPermissionRule rule : rules) {
            if (rule.matches(toolName, args)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 快捷:是否 deny。
     */
    public boolean isDeny(String toolName, Map<String, Object> args) {
        ToolPermissionRule r = match(toolName, args);
        return r != null && r.getEffect() == ToolPermissionRule.Effect.DENY;
    }

    /**
     * 快捷:是否 ask(需用户审批)。
     */
    public boolean isAsk(String toolName, Map<String, Object> args) {
        ToolPermissionRule r = match(toolName, args);
        return r != null && r.getEffect() == ToolPermissionRule.Effect.ASK;
    }
}
```

### 2.3 用户审批接口 `ApprovalUI`

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool.permission;

import java.util.Map;

/**
 * 用户审批 UI 抽象(TUI / RPC / Server 各自实现)。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public interface ApprovalUI {

    /**
     * 询问用户是否允许工具调用。
     *
     * @param toolName 工具名
     * @param args     工具参数
     * @param reason   触发审批的原因(规则描述)
     * @return true=allow, false=deny
     */
    boolean ask(String toolName, Map<String, Object> args, String reason);

    /**
     * TUI 实现:在终端显示提示,用户按 y/n。
     */
    class TerminalApprovalUI implements ApprovalUI {
        @Override
        public boolean ask(String toolName, Map<String, Object> args, String reason) {
            System.out.println("[审批] 工具 " + toolName + " 需要确认:" + reason);
            System.out.println("  参数:" + args);
            System.out.print("  允许?(y/n): ");
            try {
                int ch = System.in.read();
                return ch == 'y' || ch == 'Y';
            } catch (Exception e) {
                return false;
            }
        }
    }
}
```

### 2.4 核心:权限审批 Hook `PermissionBeforeToolCallHandler`

```java
/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.campusclaw.codingagent.tool.permission;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.BeforeToolCallHandler;
import com.campusclaw.agent.tool.BeforeToolCallResult;

/**
 * 工具调用权限审批 hook:在 ToolExecutionPipeline.execute 的 before 阶段执行。
 * <p>
 * 决策逻辑(按优先级):
 * <ol>
 *   <li>规则匹配 DENY → 直接拒绝(不执行工具)</li>
 *   <li>规则匹配 ASK → 用户审批(allow / deny)</li>
 *   <li>规则匹配 ALLOW 或无规则 → 放行</li>
 * </ol>
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/05]
 */
public class PermissionBeforeToolCallHandler implements BeforeToolCallHandler {

    private static final Logger log = LoggerFactory.getLogger(PermissionBeforeToolCallHandler.class);

    private final ToolPermissionStore ruleStore;
    private final ApprovalUI approvalUI;

    public PermissionBeforeToolCallHandler(ToolPermissionStore ruleStore, ApprovalUI approvalUI) {
        this.ruleStore = ruleStore;
        this.approvalUI = approvalUI;
    }

    @Override
    public BeforeToolCallResult handle(BeforeToolCallContext context) throws Exception {
        String toolName = context.toolCall().name();
        Map<String, Object> args = context.validatedArgs();

        // 1. 查规则
        ToolPermissionRule rule = ruleStore.match(toolName, args);

        if (rule == null) {
            // 无规则 → 默认放行
            return BeforeToolCallResult.allow();
        }

        switch (rule.getEffect()) {

            // 2. DENY → 直接拒绝
            case DENY:
                log.info("Tool call denied by rule: tool={} rule={}", toolName, rule.getDescription());
                return BeforeToolCallResult.block("Denied by rule: " + rule.getDescription());

            // 3. ASK → 用户审批
            case ASK:
                log.info("Tool call requires approval: tool={} rule={}", toolName, rule.getDescription());
                boolean approved = approvalUI.ask(toolName, args, rule.getDescription());
                if (approved) {
                    log.info("User approved tool call: tool={}", toolName);
                    return BeforeToolCallResult.allow();
                } else {
                    log.info("User denied tool call: tool={}", toolName);
                    return BeforeToolCallResult.block("User denied");
                }

            // 4. ALLOW → 放行
            case ALLOW:
            default:
                return BeforeToolCallResult.allow();
        }
    }
}
```

---

## 3. 怎么接入(不改 AgentLoop / ToolExecutionPipeline)

### 3.1 在 Agent 构造时注入

```java
// 在你的 mode 启动逻辑里(如 InteractiveMode / OneShotMode)
// 或 Agent 构造后、prompt 前设置

// 1. 创建规则存储 + 加规则
ToolPermissionStore ruleStore = new ToolPermissionStore();

// 基础只读工具:allow
ruleStore.addRule(new ToolPermissionRule("read",   ALLOW, null,  "read is safe"));
ruleStore.addRule(new ToolPermissionRule("grep",   ALLOW, null,  "grep is safe"));
ruleStore.addRule(new ToolPermissionRule("glob",   ALLOW, null,  "glob is safe"));
ruleStore.addRule(new ToolPermissionRule("ls",     ALLOW, null,  "ls is safe"));

// 写/执行工具:ask(每次问用户)
ruleStore.addRule(new ToolPermissionRule("bash",   ASK,   null,  "bash requires approval"));
ruleStore.addRule(new ToolPermissionRule("write",  ASK,   null,  "write requires approval"));
ruleStore.addRule(new ToolPermissionRule("edit",   ASK,   null,  "edit requires approval"));

// 危险路径:deny(直接拒绝)
ruleStore.addRule(new ToolPermissionRule("*",      DENY,  "/sys/**", "system directory protected"));
ruleStore.addRule(new ToolPermissionRule("*",      DENY,  "/etc/**", "etc directory protected"));

// 2. 创建审批 UI
ApprovalUI approvalUI = new ApprovalUI.TerminalApprovalUI();
// 或 RPC/Server 模式:ApprovalUI rpcApproval = new RpcApprovalUI(...);

// 3. 创建 hook + 注入
PermissionBeforeToolCallHandler permissionHook =
    new PermissionBeforeToolCallHandler(ruleStore, approvalUI);
agent.setBeforeToolCall(permissionHook);
```

### 3.2 不需要改的(确认)

| 组件 | 改不改 | 原因 |
|---|---|---|
| `AgentLoop` | **不改** | 不感知 hook(hook 在 ToolExecutionPipeline 里) |
| `ToolExecutionPipeline` | **不改** | 已内置 before hook 执行(line 87) |
| `AgentTool` 接口 | **不改** | 不加 isConcurrencySafe / checkPermissions |
| `Agent.java` | **不改** | setBeforeToolCall 已存在(line 159) |
| **新增** | 4 个类 | ToolPermissionRule / Store / ApprovalUI / PermissionBeforeToolCallHandler |

---

## 4. 完整流程

```
AgentLoop.runToolPhase
  → ToolExecutionPipeline.executeAll(calls, SEQUENTIAL)
    → execute(tool, call, args)          [每个工具]
      → applyBeforeHook                  ← 你的 PermissionBeforeToolCallHandler
        → 查规则(ruleStore.match)
          → DENY  → block("Denied by rule")        → 返 isError,工具不执行
          → ASK  → approvalUI.ask() → 用户 y/n
            → y → allow()                           → 放行
            → n → block("User denied")              → 返 isError,工具不执行
          → ALLOW / 无规则 → allow()                 → 放行
      → [如果 allow] tool.execute(...)               ← 真正执行
      → applyAfterHook
```

---

## 5. 规则配置(可选:从文件加载)

如果不想 hardcode 规则,可从配置文件加载(JSON/YAML):

```json
// ~/.campusclaw/agent/permissions.json
[
  {"tool": "read",  "effect": "allow", "description": "read is safe"},
  {"tool": "grep",  "effect": "allow", "description": "grep is safe"},
  {"tool": "bash",  "effect": "ask",   "description": "bash requires approval"},
  {"tool": "write", "effect": "ask",   "description": "write requires approval"},
  {"tool": "*",     "effect": "deny",  "path": "/sys/**", "description": "system protected"},
  {"tool": "*",     "effect": "deny",  "path": "/etc/**", "description": "etc protected"}
]
```

加载逻辑(ObjectMapper,省略):
```java
List<ToolPermissionRule> rules = mapper.readValue(
    Path.of("~/.campusclaw/agent/permissions.json"),
    new TypeReference<>() {});
rules.forEach(ruleStore::addRule);
```

---

## 6. 各模式适配

| 模式 | ApprovalUI 实现 | 行为 |
|---|---|---|
| `interactive`(TUI) | TerminalApprovalUI(终端 y/n) | 用户按键 |
| `one-shot`(非交互) | 全部 allow 或全部 deny(配置) | 无交互,自动决策 |
| `rpc` / `server` | RpcApprovalUI(转发给客户端) | 客户端 UI 审批 |

---

## 7. 设计决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 权限拦截点 | BeforeToolCallHandler(pi-mono 现有) | 不改 AgentLoop / Pipeline |
| 规则模型 | allow/deny/ask 三态(借鉴 CC/OpenCode) | 简洁,覆盖「放行/拒绝/问用户」 |
| 规则存储 | 内存 CopyOnWriteArrayList(可选文件加载) | 先简单,后续可扩展 |
| 路径匹配 | 前缀(`/**`) | 先简单,后续可换 Ant/Glob |
| ApprovalUI | 接口(多模式适配) | TUI/RPC/Server 各自实现 |
| 不加 isConcurrencySafe | 不改 AgentTool 接口 | 你不负责 agent-core,最小改动 |

---

## 8. 文件清单(新增)

```
coding-agent-cli/src/main/java/com/campusclaw/codingagent/tool/permission/
├── ToolPermissionRule.java              // 规则(工具+路径+effect)
├── ToolPermissionStore.java             // 规则存储(匹配)
├── ApprovalUI.java                      // 审批 UI 接口 + TerminalApprovalUI
└── PermissionBeforeToolCallHandler.java // 核心:BeforeToolCallHandler 实现
```

接入点:mode 启动逻辑(InteractiveMode / OneShotMode /RpcMode 等),`agent.setBeforeToolCall(permissionHook)`。
