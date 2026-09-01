/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.ThinkingLevel;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.tool.mate.MateToolSessionState;
import com.huawei.hicampus.claw.codingagent.tool.workspace.AgentWorkspaceBoundary;

/**
 * 保存单个 Session 工具装配所需的不可变上下文。
 *
 * @param entryPoint Session 创建入口
 * @param runtime 已准备的受管 Agent 运行目录
 * @param model 当前 Session 模型
 * @param thinkingLevel 当前 Session thinking 等级
 * @param workspaceBoundary 当前 Agent 工作区边界
 * @param skillIdsByName 直接绑定 Skill 名称到标识索引
 * @param childAgentNames 直接绑定 Child 名称
 * @param mateToolSessionState 当前 Session 的 Mate 工具发现状态
 * @param cronToolFactory 当前 Runtime 的 Cron 工具工厂
 * @param agentToolFactory 当前父 Session 的 Agent 工具工厂
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public record ToolAssemblyContext(
        ToolEntryPoint entryPoint,
        PreparedAgentRuntime runtime,
        Model model,
        ThinkingLevel thinkingLevel,
        AgentWorkspaceBoundary workspaceBoundary,
        Map<String, String> skillIdsByName,
        List<String> childAgentNames,
        MateToolSessionState mateToolSessionState,
        Supplier<AgentTool> cronToolFactory,
        Supplier<AgentTool> agentToolFactory) {

    public ToolAssemblyContext {
        skillIdsByName = skillIdsByName == null ? Map.of() : Map.copyOf(skillIdsByName);
        childAgentNames = childAgentNames == null ? List.of() : List.copyOf(childAgentNames);
    }
}
