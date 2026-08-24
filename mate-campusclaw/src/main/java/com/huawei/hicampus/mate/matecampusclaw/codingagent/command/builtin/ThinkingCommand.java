/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.command.builtin;

import java.util.Locale;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.SlashCommand;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.SlashCommandContext;

/**
 * 保留的 {@code /thinking} 命令处理器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class ThinkingCommand implements SlashCommand {
    @Override
    public String name() {
        return "thinking";
    }

    @Override
    public String description() {
        return "Print or switch deep thinking";
    }

    @Override
    public void execute(SlashCommandContext context, String arguments) {
        if (arguments.isBlank()) {
            context.output().println("Thinking: " + state(context.session().thinkingEnabled()));
            return;
        }
        boolean enabled = parse(arguments);
        context.session().changeThinking(enabled);
        context.output().println("Thinking: " + state(enabled));
    }

    private static boolean parse(String arguments) {
        return switch (arguments.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException("Thinking must be on or off");
        };
    }

    private static String state(boolean enabled) {
        return enabled ? "on" : "off";
    }
}
