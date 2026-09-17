/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.springframework.stereotype.Component;

/**
 * 按已提交模式和入口组合一次准备中固定的系统提示词快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class PlanPolicyPromptComposer {
    private static final String MAIN_AGENT_ROLE = "当前执行角色：Main Agent。";

    private static final String SUBAGENT_ROLE = "当前执行角色：Subagent；只向父 Agent 返回结果。";

    /**
     * 依据入口矩阵从同一快照完整重组 SYSTEM。
     *
     * @param prompts 本次执行的固定提示词快照
     * @param mode 已提交的 Session 模式
     * @param entryPoint 工具入口
     * @return 最终 SYSTEM
     */
    public String compose(AgentPromptSnapshotDTO prompts, String mode, ToolEntryPoint entryPoint) {
        if (entryPoint == ToolEntryPoint.CRON) {
            return prompts.basePrompt();
        }
        if (entryPoint == ToolEntryPoint.CHILD_AGENT && !ClawConstants.Agent.MODE_PLAN.equals(mode)) {
            return prompts.basePrompt();
        }
        String role = entryPoint == ToolEntryPoint.RUNTIME ? MAIN_AGENT_ROLE : SUBAGENT_ROLE;
        String modePrompt = ClawConstants.Agent.MODE_PLAN.equals(mode) ? prompts.planPrompt() : prompts.buildPrompt();
        return append(append(prompts.basePrompt(), role), modePrompt);
    }

    public boolean planModeAvailable(List<String> toolNames) {
        return toolNames.contains(BuiltInToolName.EXIT_PLAN_MODE.externalName());
    }

    private static String append(String current, String addition) {
        if (current.isBlank()) {
            return addition.strip();
        }
        return current.stripTrailing() + "\n\n" + addition.strip();
    }
}
