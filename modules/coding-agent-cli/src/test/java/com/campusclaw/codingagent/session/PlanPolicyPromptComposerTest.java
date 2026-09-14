/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.Test;

/**
 * 验证五段固定提示词按入口、模式及真实工具快照隔离组合。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class PlanPolicyPromptComposerTest {
    private final PlanPolicyPromptComposer composer = new PlanPolicyPromptComposer();

    @Test
    void combinesRuntimePlanWithoutImplementationTodoGuidance() {
        String prompt = composer.compose("BASE", ToolEntryPoint.RUNTIME, "plan", List.of("ExitPlanMode", "TodoUpdate"));

        assertThat(prompt)
                .startsWith("BASE\n\n你当前处于计划模式")
                .contains("你负责与用户澄清并提交完整方案")
                .doesNotContain("你是规划任务的 Child Agent", "你当前处于 build 模式", "TodoUpdate 用于 build 阶段");
    }

    @Test
    void combinesChildPlanWithoutRootReviewGuidance() {
        String prompt = composer.compose("BASE", ToolEntryPoint.CHILD_AGENT, "plan", List.of());

        assertThat(prompt)
                .startsWith("BASE\n\n你当前处于计划模式")
                .contains("你是规划任务的 Child Agent")
                .doesNotContain("你负责与用户澄清并提交完整方案", "TodoUpdate 用于 build 阶段");
    }

    @Test
    void keepsChildBuildAndCronPromptsUnchanged() {
        assertThat(composer.compose("BASE", ToolEntryPoint.CHILD_AGENT, "build", List.of("TodoUpdate")))
                .isEqualTo("BASE");
        assertThat(composer.compose("BASE", ToolEntryPoint.CRON, "build", List.of("TodoUpdate")))
                .isEqualTo("BASE");
        assertThat(composer.compose("BASE", ToolEntryPoint.CRON, "plan", List.of("ExitPlanMode")))
                .isEqualTo("BASE");
    }

    @Test
    void addsTodoGuidanceOnlyWhenRuntimeBuildActuallyHasTheTool() {
        String withTodo = composer.compose("BASE", ToolEntryPoint.RUNTIME, "build", List.of("TodoUpdate"));
        String withoutTodo = composer.compose("BASE", ToolEntryPoint.RUNTIME, "build", List.of());

        assertThat(withTodo).contains("你当前处于 build 模式", "TodoUpdate 用于 build 阶段");
        assertThat(withoutTodo).contains("你当前处于 build 模式").doesNotContain("TodoUpdate 用于 build 阶段");
        assertThat(composer.planModeAvailable(List.of("ExitPlanMode"))).isTrue();
        assertThat(composer.planModeAvailable(List.of("TodoUpdate"))).isFalse();
    }
}
