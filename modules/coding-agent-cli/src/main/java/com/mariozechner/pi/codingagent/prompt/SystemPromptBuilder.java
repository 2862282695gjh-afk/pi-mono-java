package com.mariozechner.pi.codingagent.prompt;

import com.mariozechner.pi.agent.tool.AgentTool;
import org.springframework.stereotype.Service;

/**
 * Builds the system prompt from base instructions, tool descriptions,
 * skill listings, environment info, and user customizations.
 */
@Service
public class SystemPromptBuilder {

    static final String BASE_PROMPT = """
            You are an interactive agent that helps users with software engineering tasks.
            You have access to a set of tools that you can use to answer the user's question.

            You are an expert software engineer. You can read, write, and edit files, \
            search codebases, and execute shell commands.

            Always prefer editing existing files over creating new ones. \
            Understand existing code before suggesting modifications. \
            Be careful not to introduce security vulnerabilities.""";

    /**
     * Builds the complete system prompt from the given configuration.
     *
     * @param config the prompt configuration
     * @return the assembled system prompt string
     */
    public String build(SystemPromptConfig config) {
        var sb = new StringBuilder();

        // 1. Base role definition
        sb.append(BASE_PROMPT);

        // 2. Tool descriptions
        if (!config.tools().isEmpty()) {
            sb.append("\n\n# Tools\n\n");
            sb.append("You have access to the following tools:\n");
            for (AgentTool tool : config.tools()) {
                sb.append("\n## ").append(tool.name()).append('\n');
                sb.append(tool.description()).append('\n');
                sb.append("Parameters: ").append(tool.parameters().toString()).append('\n');
            }
        }

        // 3. Skills
        if (!config.skills().isEmpty()) {
            sb.append("\n\n# Skills\n\n");
            sb.append("<skills>\n");
            for (Skill skill : config.skills()) {
                sb.append("  <skill name=\"").append(escapeXml(skill.name())).append("\"");
                sb.append(" location=\"").append(escapeXml(skill.location())).append("\">");
                sb.append(escapeXml(skill.description()));
                sb.append("</skill>\n");
            }
            sb.append("</skills>");
        }

        // 4. Environment info
        sb.append("\n\n# Environment\n\n");
        appendEnvironmentInfo(sb, config);

        // 5. User custom prompt
        if (config.customPrompt() != null && !config.customPrompt().isBlank()) {
            sb.append("\n\n# User Instructions\n\n");
            sb.append(config.customPrompt());
        }

        return sb.toString();
    }

    private void appendEnvironmentInfo(StringBuilder sb, SystemPromptConfig config) {
        sb.append("- Working directory: ").append(config.cwd()).append('\n');

        String os = config.env().getOrDefault("OS_NAME", System.getProperty("os.name", "unknown"));
        sb.append("- Operating system: ").append(os).append('\n');

        String gitBranch = config.env().get("GIT_BRANCH");
        if (gitBranch != null && !gitBranch.isBlank()) {
            sb.append("- Git branch: ").append(gitBranch).append('\n');
        }

        String javaVersion = config.env().getOrDefault("JAVA_VERSION",
                System.getProperty("java.version", "unknown"));
        sb.append("- Java version: ").append(javaVersion);
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
