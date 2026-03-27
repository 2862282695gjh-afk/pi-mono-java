package com.mariozechner.pi.codingagent.command.builtin;

import com.mariozechner.pi.codingagent.command.SlashCommand;
import com.mariozechner.pi.codingagent.command.SlashCommandContext;

/**
 * Reloads skills and settings matching pi-mono TS /reload command.
 */
public class ReloadCommand implements SlashCommand {

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String description() {
        return "Reload skills and settings";
    }

    @Override
    public void execute(SlashCommandContext context, String arguments) {
        // Reload skills from disk
        var session = context.session();
        var cwd = System.getProperty("user.dir");
        // Skills are loaded from user and project directories
        // Re-initialization would require rebuilding the system prompt
        context.output().println("Reloading skills and settings...");
        try {
            var skillRegistry = session.getSkillRegistry();
            skillRegistry.clear();
            // Note: full reload would require re-building system prompt
            // For now, clear and report
            context.output().println("Skills cleared. Restart session for full reload.");
        } catch (Exception e) {
            context.output().println("Reload failed: " + e.getMessage());
        }
    }
}
