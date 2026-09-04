/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import java.util.List;

/**
 * Immutable result of resolving one command for a session. Listing, admission and
 * exact-name lookup all read this object; mutable transport DTOs are never part of
 * the resolved state.
 *
 * @param name stable command name without leading slash
 * @param kind command kind
 * @param description short usage description
 * @param available whether the command can execute without arguments
 * @param unavailableCode stable reason code when not available
 * @param input argument input availability of the command
 * @param snapshot versioned Skill identity captured at resolution time; null for
 *        kinds that carry no Skill snapshot
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record ResolvedCommand(
        String name,
        CommandKind kind,
        String description,
        boolean available,
        String unavailableCode,
        Input input,
        SkillCommandSnapshot snapshot) {

    /**
     * Immutable argument input availability of a resolved command.
     *
     * @param mode lowercase input mode, none, optional or required
     * @param available whether the command can execute with arguments
     * @param unavailableCode stable reason code when argument execution is unavailable
     * @param acceptsFiles whether attachments are accepted with arguments
     * @param placeholder argument placeholder for input hints
     * @param suggestions static argument suggestions for input hints
     */
    public record Input(
            String mode,
            boolean available,
            String unavailableCode,
            boolean acceptsFiles,
            String placeholder,
            List<String> suggestions) {}
}
