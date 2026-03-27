package com.mariozechner.pi.codingagent.command.builtin;

import com.mariozechner.pi.codingagent.command.SlashCommand;
import com.mariozechner.pi.codingagent.command.SlashCommandContext;

/**
 * Shows session info and stats matching pi-mono TS /session command.
 */
public class SessionCommand implements SlashCommand {

    @Override
    public String name() {
        return "session";
    }

    @Override
    public String description() {
        return "Show session info and stats";
    }

    @Override
    public void execute(SlashCommandContext context, String arguments) {
        var session = context.session();
        var state = session.getAgent().getState();
        var model = state.getModel();

        context.output().println("Session Info:");
        context.output().println("  Model: " + (model != null ? model.id() : "unknown"));
        if (model != null && model.provider() != null) {
            context.output().println("  Provider: " + model.provider().name());
        }
        if (model != null && model.contextWindow() > 0) {
            context.output().println("  Context window: " + formatTokens(model.contextWindow()));
        }

        var messages = state.getMessages();
        int userMessages = 0;
        int assistantMessages = 0;
        for (var msg : messages) {
            if (msg instanceof com.mariozechner.pi.ai.types.UserMessage) userMessages++;
            else if (msg instanceof com.mariozechner.pi.ai.types.AssistantMessage) assistantMessages++;
        }
        context.output().println("  Messages: " + messages.size()
                + " (" + userMessages + " user, " + assistantMessages + " assistant)");

        var thinkingLevel = state.getThinkingLevel();
        if (thinkingLevel != null) {
            context.output().println("  Thinking: " + thinkingLevel.value());
        }
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1000) return String.format("%.0fk", tokens / 1000.0);
        return String.valueOf(tokens);
    }
}
