/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.command.builtin;

import com.campusclaw.codingagent.command.SlashCommand;
import com.campusclaw.codingagent.command.SlashCommandContext;

/**
 * 保留的 {@code /model} 命令处理器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class ModelCommand implements SlashCommand {
    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Print or switch the current model";
    }

    @Override
    public void execute(SlashCommandContext context, String arguments) {
        if (arguments.isBlank()) {
            context.output().println("Current model: " + context.session().currentModelId());
            return;
        }
        String modelId = arguments.trim();
        context.session().changeModel(modelId);
        context.output().println("Switched to model: " + modelId);
    }
}
