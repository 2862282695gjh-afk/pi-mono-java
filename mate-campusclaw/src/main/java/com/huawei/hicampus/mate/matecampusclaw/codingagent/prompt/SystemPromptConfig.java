/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.prompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.context.ContextFileLoader.ContextFile;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.skill.Skill;

/**
 * Configuration for building the system prompt.
 *
 * @param tools              registered agent tools
 * @param skills             available skills
 * @param cwd                current working directory
 * @param customPrompt       user-supplied additional prompt text (may be null)
 * @param env                environment variables snapshot (may be null or empty)
 * @param contextFiles       AGENTS.md/CLAUDE.md context files (may be null or empty)
 * @param systemPromptOverride  content of SYSTEM.md if found (replaces base prompt; may be null)
 * @param appendSystemPrompt content of APPEND_SYSTEM.md if found (may be null)
 * @param skillActivationRequired whether Skills must be activated through {@code activate_skill}
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record SystemPromptConfig(
        List<AgentTool> tools,
        List<Skill> skills,
        Path cwd,
        String customPrompt,
        Map<String, String> env,
        List<ContextFile> contextFiles,
        String systemPromptOverride,
        String appendSystemPrompt,
        boolean skillActivationRequired) {
    public SystemPromptConfig {
        tools = tools != null ? List.copyOf(tools) : List.of();
        skills = skills != null ? List.copyOf(skills) : List.of();
        env = env != null ? Map.copyOf(env) : Map.of();
        contextFiles = contextFiles != null ? List.copyOf(contextFiles) : List.of();
    }

    /**
     * Backwards-compatible constructor used before the context-files / system-prompt-override /
     * append-prompt fields were added. Delegates to the canonical constructor with those
     * optional fields defaulted to an empty list and {@code null}.
     *
     * @param tools registered agent tools
     * @param skills available skills
     * @param cwd current working directory
     * @param customPrompt user-supplied additional prompt text (may be null)
     * @param env environment variables snapshot (may be null)
     */
    public SystemPromptConfig(
            List<AgentTool> tools, List<Skill> skills, Path cwd, String customPrompt, Map<String, String> env) {
        this(tools, skills, cwd, customPrompt, env, List.of(), null, null, false);
    }

    /**
     * Backwards-compatible constructor for unmanaged Skill loading.
     *
     * @param tools registered Agent tools
     * @param skills available Skills
     * @param cwd working directory
     * @param customPrompt additional prompt
     * @param env environment snapshot
     * @param contextFiles project context files
     * @param systemPromptOverride replacement system prompt
     * @param appendSystemPrompt appended system prompt
     */
    public SystemPromptConfig(
            List<AgentTool> tools,
            List<Skill> skills,
            Path cwd,
            String customPrompt,
            Map<String, String> env,
            List<ContextFile> contextFiles,
            String systemPromptOverride,
            String appendSystemPrompt) {
        this(tools, skills, cwd, customPrompt, env, contextFiles, systemPromptOverride, appendSystemPrompt, false);
    }
}
