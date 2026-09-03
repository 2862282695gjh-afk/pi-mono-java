/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.springframework.stereotype.Service;

/**
 * Combines all {@link CommandDefinitionSource} beans and resolves one validated
 * {@link ResolvedCommandCatalog} per request, so listing and exact-name lookup
 * share a single resolution of the dynamic sources. The registry contains no
 * command branching; adding a command only means adding a source.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CompositeCommandRegistry {
    private final List<CommandDefinitionSource> sources;

    public CompositeCommandRegistry(List<CommandDefinitionSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * Resolves the command catalog visible to the session for a single request.
     *
     * @param session authorized session providing the agent scope
     * @return validated catalog with definitions sorted by command name
     * @throws IllegalStateException when two sources contribute the same command name
     */
    public ResolvedCommandCatalog resolve(RuntimeSessionDTO session) {
        List<CommandDefinition> definitions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CommandDefinitionSource source : sources) {
            for (CommandDefinition definition : source.list(session)) {
                if (!seen.add(definition.name())) {
                    throw new IllegalStateException("Duplicate command name: " + definition.name());
                }
                definitions.add(definition);
            }
        }
        definitions.sort(Comparator.comparing(CommandDefinition::name));
        return new ResolvedCommandCatalog(definitions);
    }
}
