/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.common.constant.ClawConstants;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 按已提交模式、入口和实际工具快照组合固定系统提示词。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class PlanPolicyPromptComposer implements InitializingBean {
    private final String planCommon = load("PLAN_COMMON");

    private final String planRootReview = load("PLAN_ROOT_REVIEW");

    private final String planChild = load("PLAN_CHILD");

    private final String buildRoot = load("BUILD_ROOT");

    private final String executionTodos = load("EXECUTION_TODOS");

    private final String askUser = load("ASK_USER");

    @Override
    public void afterPropertiesSet() {
        for (String prompt : List.of(planCommon, planRootReview, planChild, buildRoot, executionTodos, askUser)) {
            if (prompt.isBlank()) {
                throw new IllegalStateException("Plan policy prompt must not be blank");
            }
        }
    }

    public String compose(String base, ToolEntryPoint entryPoint, String mode, List<String> toolNames) {
        if (ClawConstants.Agent.MODE_PLAN.equals(mode)) {
            return planPrompt(base, entryPoint, toolNames);
        }
        if (entryPoint != ToolEntryPoint.RUNTIME) {
            return base;
        }
        String prompt = append(base, buildRoot);
        if (toolNames.contains(BuiltInToolName.ASK_USER_QUESTION.externalName())) {
            prompt = append(prompt, askUser);
        }
        return toolNames.contains(BuiltInToolName.TODO_UPDATE.externalName()) ? append(prompt, executionTodos) : prompt;
    }

    public boolean planModeAvailable(List<String> toolNames) {
        return !planCommon.isBlank()
                && !planRootReview.isBlank()
                && toolNames.contains(BuiltInToolName.EXIT_PLAN_MODE.externalName());
    }

    private String planPrompt(String base, ToolEntryPoint entryPoint, List<String> toolNames) {
        String prompt = append(base, planCommon);
        prompt = switch (entryPoint) {
            case RUNTIME -> append(prompt, planRootReview);
            case CHILD_AGENT -> append(prompt, planChild);
            case CRON -> base;
        };
        return entryPoint == ToolEntryPoint.RUNTIME
                        && toolNames.contains(BuiltInToolName.ASK_USER_QUESTION.externalName())
                ? append(prompt, askUser)
                : prompt;
    }

    private static String append(String current, String addition) {
        return current.stripTrailing() + "\n\n" + addition.strip();
    }

    private static String load(String name) {
        var resource = new ClassPathResource("prompts/plan/" + name + ".txt");
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Plan policy prompt is unavailable: " + name, error);
        }
    }
}
