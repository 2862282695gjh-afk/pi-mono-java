/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.command.builtin;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.SlashCommand;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.SlashCommandContext;

/**
 * 保留的 {@code /compact} 命令处理器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class CompactCommand implements SlashCommand {
    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compact session context";
    }

    @Override
    public void execute(SlashCommandContext context, String arguments) {
        String instructions = arguments.isBlank() ? null : arguments.trim();
        var result = context.session().compact(instructions);
        context.output().println("Compacted context to " + result.estimatedTokensAfter() + " estimated tokens.");
    }
}
