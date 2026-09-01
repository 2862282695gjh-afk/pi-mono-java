/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.command;

import java.util.Objects;

/**
 * Slash Command 执行时使用的宿主无关上下文。
 *
 * @param session Session 操作端口
 * @param output 文本结果端口
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record SlashCommandContext(SlashCommandSession session, SlashCommandOutput output) {
    public SlashCommandContext {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(output, "output");
    }
}
