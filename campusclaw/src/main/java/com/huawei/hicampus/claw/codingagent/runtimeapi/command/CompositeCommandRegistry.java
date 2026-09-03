/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.springframework.stereotype.Service;

/**
 * Combines all {@link CommandDefinitionSource} beans and resolves command
 * definitions by exact name. The registry contains no command branching; adding a
 * command only means adding a source.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CompositeCommandRegistry {
    private final List<CommandDefinitionSource> sources;

    public CompositeCommandRegistry(List<CommandDefinitionSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * Lists all command definitions visible to the session, sorted by command name.
     *
     * @param session authorized session providing the agent scope
     * @return combined definitions; duplicated names fail fast
     * @throws IllegalStateException when two sources contribute the same command name
     */
    public List<CommandDefinition> list(RuntimeSessionDTO session) {
        return resolve(session);
    }

    /**
     * Finds one command definition by exact command name within the validated merged
     * view, so a duplicate name from any source can never be silently resolved.
     *
     * @param session authorized session providing the agent scope
     * @param name command name without leading slash
     * @return matching definition from the merged view
     * @throws IllegalStateException when two sources contribute the same command name
     */
    public Optional<CommandDefinition> find(RuntimeSessionDTO session, String name) {
        return resolve(session).stream()
                .filter(definition -> definition.descriptor().getName().equals(name))
                .findFirst();
    }

    private List<CommandDefinition> resolve(RuntimeSessionDTO session) {
        List<CommandDefinition> definitions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CommandDefinitionSource source : sources) {
            for (CommandDefinition definition : source.list(session)) {
                if (!seen.add(definition.descriptor().getName())) {
                    throw new IllegalStateException(
                            "Duplicate command name: " + definition.descriptor().getName());
                }
                definitions.add(definition);
            }
        }
        definitions.sort(
                Comparator.comparing(definition -> definition.descriptor().getName()));
        return List.copyOf(definitions);
    }
}
