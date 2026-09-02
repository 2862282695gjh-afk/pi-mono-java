/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.function.Supplier;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.codingagent.tool.find.FindTool;
import com.huawei.hicampus.claw.codingagent.tool.grep.GrepTool;
import com.huawei.hicampus.claw.codingagent.tool.ls.LsTool;
import com.huawei.hicampus.claw.codingagent.tool.ops.FindOperations;
import com.huawei.hicampus.claw.codingagent.tool.ops.GrepOperations;
import com.huawei.hicampus.claw.codingagent.tool.ops.LsOperations;
import com.huawei.hicampus.claw.codingagent.tool.ops.ReadOperations;
import com.huawei.hicampus.claw.codingagent.tool.read.ReadTool;
import com.huawei.hicampus.claw.codingagent.tool.workspace.WorkspacePathResolver;

import org.springframework.stereotype.Component;

/**
 * 使用关闭枚举和 Session 上下文创建八种活动内置工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class DefaultBuiltInToolFactory implements BuiltInToolFactory {

    private final ReadOperations readOperations;

    private final FindOperations findOperations;

    private final GrepOperations grepOperations;

    private final LsOperations lsOperations;

    private final WorkspacePathResolver pathResolver;

    public DefaultBuiltInToolFactory(
            ReadOperations readOperations,
            FindOperations findOperations,
            GrepOperations grepOperations,
            LsOperations lsOperations,
            WorkspacePathResolver pathResolver) {
        this.readOperations = readOperations;
        this.findOperations = findOperations;
        this.grepOperations = grepOperations;
        this.lsOperations = lsOperations;
        this.pathResolver = pathResolver;
    }

    @Override
    public AgentTool create(BuiltInToolName name, ToolAssemblyContext context) {
        return switch (name) {
            case READ -> new ReadTool(readOperations, pathResolver, context.workspaceBoundary());
            case FIND -> new FindTool(findOperations, pathResolver, context.workspaceBoundary());
            case GREP -> new GrepTool(grepOperations, pathResolver, context.workspaceBoundary());
            case LS -> new LsTool(lsOperations, pathResolver, context.workspaceBoundary());
            case CRON -> contextualTool(name, context.cronToolFactory(), context);
            case LIST_MATE_TOOLS ->
                context.mateToolSessionState() == null
                        ? unavailable(name, context)
                        : context.mateToolSessionState().createListTool();
            case CALL_MATE_TOOL ->
                context.mateToolSessionState() == null
                        ? unavailable(name, context)
                        : context.mateToolSessionState().createCallTool();
            case AGENT -> contextualTool(name, context.agentToolFactory(), context);
        };
    }

    private static AgentTool contextualTool(
            BuiltInToolName name, Supplier<AgentTool> supplier, ToolAssemblyContext context) {
        return supplier == null ? unavailable(name, context) : supplier.get();
    }

    private static AgentTool unavailable(BuiltInToolName name, ToolAssemblyContext context) {
        return new UnavailableBuiltInTool(name, BuiltInToolContracts.parameters(name, context.childAgentNames()));
    }
}
