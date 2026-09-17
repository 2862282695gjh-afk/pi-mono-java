/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.campusclaw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO.PromptSource;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.Test;

/**
 * 验证固定提示词快照按入口和模式矩阵完整组合。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
class PlanPolicyPromptComposerTest {
    private final PlanPolicyPromptComposer composer = new PlanPolicyPromptComposer();

    private final AgentPromptSnapshotDTO prompts = new AgentPromptSnapshotDTO(
            "BASE", "PLAN BODY", "BUILD BODY", PromptSource.AGENT_FILE, PromptSource.BUILTIN);

    @Test
    void combinesRuntimeModesFromTheSameSnapshot() {
        assertThat(composer.compose(prompts, "plan", ToolEntryPoint.RUNTIME))
                .isEqualTo("BASE\n\n当前执行角色：Main Agent。\n\nPLAN BODY");
        assertThat(composer.compose(prompts, "build", ToolEntryPoint.RUNTIME))
                .isEqualTo("BASE\n\n当前执行角色：Main Agent。\n\nBUILD BODY");
    }

    @Test
    void addsSubagentRoleOnlyForChildPlan() {
        assertThat(composer.compose(prompts, "plan", ToolEntryPoint.CHILD_AGENT))
                .isEqualTo("BASE\n\n当前执行角色：Subagent；只向父 Agent 返回结果。\n\nPLAN BODY");
        assertThat(composer.compose(prompts, "build", ToolEntryPoint.CHILD_AGENT))
                .isEqualTo("BASE");
    }

    @Test
    void keepsCronUnchangedInBothModes() {
        assertThat(composer.compose(prompts, "plan", ToolEntryPoint.CRON)).isEqualTo("BASE");
        assertThat(composer.compose(prompts, "build", ToolEntryPoint.CRON)).isEqualTo("BASE");
    }

    @Test
    void repeatedCompositionDoesNotAccumulateModeText() {
        String plan = composer.compose(prompts, "plan", ToolEntryPoint.RUNTIME);
        String build = composer.compose(prompts, "build", ToolEntryPoint.RUNTIME);

        assertThat(plan).containsOnlyOnce("PLAN BODY").doesNotContain("BUILD BODY");
        assertThat(build).containsOnlyOnce("BUILD BODY").doesNotContain("PLAN BODY");
        assertThat(composer.compose(prompts, "plan", ToolEntryPoint.RUNTIME)).isEqualTo(plan);
    }

    @Test
    void planAvailabilityDependsOnlyOnExitTool() {
        assertThat(composer.planModeAvailable(List.of("ExitPlanMode"))).isTrue();
        assertThat(composer.planModeAvailable(List.of("TodoUpdate", "AskUserQuestion")))
                .isFalse();
    }
}
