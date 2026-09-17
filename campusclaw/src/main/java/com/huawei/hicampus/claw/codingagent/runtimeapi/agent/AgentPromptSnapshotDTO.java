/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.agent;

import java.util.Objects;

/**
 * 保存一次 Agent 准备得到的基础提示词和两种模式正文。
 *
 * @param basePrompt 受管 SYSTEM 与可见 Skill 组合后的基础提示词
 * @param planPrompt 已选择的 Plan 模式正文
 * @param buildPrompt 已选择的 Build 模式正文
 * @param planSource Plan 模式正文来源
 * @param buildSource Build 模式正文来源
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record AgentPromptSnapshotDTO(
        String basePrompt, String planPrompt, String buildPrompt, PromptSource planSource, PromptSource buildSource) {
    public AgentPromptSnapshotDTO {
        basePrompt = Objects.requireNonNull(basePrompt, "basePrompt");
        planPrompt = requireModePrompt(planPrompt, "planPrompt");
        buildPrompt = requireModePrompt(buildPrompt, "buildPrompt");
        planSource = Objects.requireNonNull(planSource, "planSource");
        buildSource = Objects.requireNonNull(buildSource, "buildSource");
    }

    private static String requireModePrompt(String prompt, String name) {
        Objects.requireNonNull(prompt, name);
        if (prompt.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return prompt;
    }

    /**
     * 标识模式正文来自业务 Agent 文件或内置资源。
     */
    public enum PromptSource {
        AGENT_FILE,
        BUILTIN
    }
}
