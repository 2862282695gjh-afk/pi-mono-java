package com.mariozechner.pi.codingagent.command.builtin;

import com.mariozechner.pi.codingagent.command.SlashCommand;
import com.mariozechner.pi.codingagent.command.SlashCommandContext;

public class NewCommand implements SlashCommand {

    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Start a new session";
    }

    @Override
    public void execute(SlashCommandContext context, String arguments) {
        context.session().newSession();
        context.output().println("Started new session. Conversation history cleared.");
    }
}
