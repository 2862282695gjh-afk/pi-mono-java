/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.command;

/**
 * 与具体 Host 无关的 Slash Command 契约。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public interface SlashCommand {
    String name();

    String description();

    void execute(SlashCommandContext context, String arguments);
}
