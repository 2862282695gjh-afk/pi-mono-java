/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.List;
import java.util.Optional;

/**
 * Immutable per-request resolution result of {@link CompositeCommandRegistry}. One
 * catalog instance serves command listing and exact-name lookup for the same
 * request, so display, admission and execution read a single validated view of
 * deeply immutable {@link ResolvedCommand} values.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ResolvedCommandCatalog {
    private final List<ResolvedCommand> commands;

    ResolvedCommandCatalog(List<ResolvedCommand> commands) {
        this.commands = List.copyOf(commands);
    }

    /**
     * Returns all resolved commands of this resolution, sorted by command name.
     *
     * @return immutable command list
     */
    public List<ResolvedCommand> list() {
        return commands;
    }

    /**
     * Finds one resolved command by exact command name.
     *
     * @param name command name without leading slash
     * @return command when present in this resolution
     */
    public Optional<ResolvedCommand> find(String name) {
        return commands.stream().filter(command -> command.name().equals(name)).findFirst();
    }
}
