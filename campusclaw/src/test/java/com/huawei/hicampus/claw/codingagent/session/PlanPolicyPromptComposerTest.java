/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.Test;

/**
 * 验证六段固定提示词按入口、模式及真实工具快照隔离组合。
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
                .contains("你负责调研、必要澄清和提交完整方案")
                .doesNotContain("你是规划任务的 Child Agent", "你当前处于 build 模式", "TodoUpdate 用于 build 阶段");
    }

    @Test
    void combinesChildPlanWithoutRootReviewGuidance() {
        String prompt = composer.compose("BASE", ToolEntryPoint.CHILD_AGENT, "plan", List.of());

        assertThat(prompt)
                .startsWith("BASE\n\n你当前处于计划模式")
                .contains("你是规划任务的 Child Agent")
                .doesNotContain("你负责调研、必要澄清和提交完整方案", "TodoUpdate 用于 build 阶段");
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

    @Test
    void guidesRootToInspectFactsAndAskOnlyNecessaryQuestions() {
        String plan =
                composer.compose("BASE", ToolEntryPoint.RUNTIME, "plan", List.of("ExitPlanMode", "AskUserQuestion"));
        String build = composer.compose("BASE", ToolEntryPoint.RUNTIME, "build", List.of("AskUserQuestion"));

        assertThat(plan)
                .contains("能够查明的事实自行查明", "没有待决问题就直接形成方案", "需要提问时优先只问当前一个关键问题")
                .contains("不为用满工具容量而凑题", "工具 JSON 用于理解答案和继续执行")
                .contains("问题回答不批准整份计划、不切换模式，也不代替业务工具权限确认");
        assertThat(build).contains("先完成不依赖答案的必要检查", "上下文已足够时直接继续").contains("工具 JSON 用于理解答案和继续执行");
    }
}
