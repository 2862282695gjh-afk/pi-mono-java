/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.command.builtin;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.SlashCommand;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.command.SlashCommandContext;

/**
 * 保留的 {@code /name} 命令处理器。
 *
 * <p>显示名称的读写由未来宿主适配 mate-service，不进入 CampusClaw Runtime Session。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public class NameCommand implements SlashCommand {
    @Override
    public String name() {
        return "name";
    }

    @Override
    public String description() {
        return "Set session display name";
    }

    @Override
    public void execute(SlashCommandContext context, String arguments) {
        if (arguments.isBlank()) {
            String name = context.session().displayName().orElse("not set");
            context.output().println("Session name: " + name);
            return;
        }
        String name = arguments.trim();
        context.session().changeDisplayName(name);
        context.output().println("Session name set to: " + name);
    }
}
