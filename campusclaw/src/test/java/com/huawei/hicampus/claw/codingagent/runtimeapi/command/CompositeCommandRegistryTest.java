/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.junit.jupiter.api.Test;

/**
 * Source composition, duplicate detection and exact-name lookup of the command
 * registry.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
class CompositeCommandRegistryTest {

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    @Test
    void mergesSourcesAndSortsByName() {
        CompositeCommandRegistry registry =
                new CompositeCommandRegistry(List.of(source("skill:zeta", "skill:alpha"), source("skill:middle")));

        assertThat(registry.list(session))
                .extracting(definition -> definition.descriptor().getName())
                .containsExactly("skill:alpha", "skill:middle", "skill:zeta");
    }

    @Test
    void duplicateNameAcrossSourcesFailsFast() {
        CompositeCommandRegistry registry =
                new CompositeCommandRegistry(List.of(source("skill:dup"), source("skill:dup")));

        assertThatThrownBy(() -> registry.list(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill:dup");
    }

    @Test
    void findAlsoFailsFastOnDuplicateNames() {
        CompositeCommandRegistry registry =
                new CompositeCommandRegistry(List.of(source("skill:dup", "skill:other"), source("skill:dup")));

        assertThatThrownBy(() -> registry.find(session, "skill:dup"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill:dup");

        assertThatThrownBy(() -> registry.find(session, "skill:other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill:dup");
    }

    @Test
    void findReturnsFirstMatchingSourceResult() {
        CommandDefinitionSource first = source("skill:one");
        CommandDefinitionSource second = source("skill:two");
        CompositeCommandRegistry registry = new CompositeCommandRegistry(List.of(first, second));

        assertThat(registry.find(session, "skill:one")).isPresent();
        assertThat(registry.find(session, "skill:two")).isPresent();
        assertThat(registry.find(session, "skill:missing")).isEmpty();
    }

    private CommandDefinitionSource source(String... names) {
        return new CommandDefinitionSource() {
            @Override
            public List<CommandDefinition> list(RuntimeSessionDTO sessionView) {
                return java.util.Arrays.stream(names)
                        .map(CompositeCommandRegistryTest.this::definition)
                        .toList();
            }

            @Override
            public Optional<CommandDefinition> find(RuntimeSessionDTO sessionView, String name) {
                return java.util.Arrays.stream(names)
                        .filter(candidate -> candidate.equals(name))
                        .map(CompositeCommandRegistryTest.this::definition)
                        .findFirst();
            }
        };
    }

    private CommandDefinition definition(String name) {
        CommandDescriptorDTO descriptor = new CommandDescriptorDTO();
        descriptor.setName(name);
        descriptor.setKind(CommandKind.SKILL);
        descriptor.setDescription("description");
        descriptor.setAvailable(true);
        return new CommandDefinition(descriptor);
    }
}
