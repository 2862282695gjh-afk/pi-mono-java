/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import java.util.List;
import java.util.Optional;

/**
 * Immutable per-request resolution result of {@link CompositeCommandRegistry}. One
 * catalog instance serves command listing and exact-name lookup for the same
 * request, so display, admission and execution read a single validated view.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ResolvedCommandCatalog {
    private final List<CommandDefinition> definitions;

    ResolvedCommandCatalog(List<CommandDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
    }

    /**
     * Returns all definitions of this resolution, sorted by command name.
     *
     * @return immutable definition list
     */
    public List<CommandDefinition> list() {
        return definitions;
    }

    /**
     * Finds one definition by exact command name.
     *
     * @param name command name without leading slash
     * @return definition when present in this resolution
     */
    public Optional<CommandDefinition> find(String name) {
        return definitions.stream()
                .filter(definition -> definition.name().equals(name))
                .findFirst();
    }
}
