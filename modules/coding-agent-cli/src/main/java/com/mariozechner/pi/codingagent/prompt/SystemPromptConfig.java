package com.mariozechner.pi.codingagent.prompt;

import com.mariozechner.pi.agent.tool.AgentTool;
import com.mariozechner.pi.codingagent.skill.Skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Configuration for building the system prompt.
 *
 * @param tools        registered agent tools
 * @param skills       available skills
 * @param cwd          current working directory
 * @param customPrompt user-supplied additional prompt text (may be null)
 * @param env          environment variables snapshot (may be null or empty)
 */
public record SystemPromptConfig(
        List<AgentTool> tools,
        List<Skill> skills,
        Path cwd,
        String customPrompt,
        Map<String, String> env
) {
    public SystemPromptConfig {
        tools = tools != null ? List.copyOf(tools) : List.of();
        skills = skills != null ? List.copyOf(skills) : List.of();
        env = env != null ? Map.copyOf(env) : Map.of();
    }
}
