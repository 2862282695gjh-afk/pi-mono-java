/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.command;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSession;

/**
 * Context passed to slash commands for accessing session, printing output, etc.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record SlashCommandContext(AgentSession session, OutputWriter output) {
    @SuppressWarnings("checkstyle:top_class_comment")
    @FunctionalInterface
    public interface OutputWriter {
        void println(String message);
    }
}
