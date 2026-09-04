/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.junit.jupiter.api.Test;

/**
 * Source composition, duplicate detection and exact-name lookup of the per-request
 * resolved command catalog.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
class CompositeCommandRegistryTest {

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    @Test
    void resolveMergesSourcesAndSortsByName() {
        CompositeCommandRegistry registry =
                new CompositeCommandRegistry(List.of(source("skill:zeta", "skill:alpha"), source("skill:middle")));

        assertThat(registry.resolve(session).list())
                .extracting(ResolvedCommand::name)
                .containsExactly("skill:alpha", "skill:middle", "skill:zeta");
    }

    @Test
    void resolveFailsFastOnDuplicateNames() {
        CompositeCommandRegistry registry =
                new CompositeCommandRegistry(List.of(source("skill:dup"), source("skill:dup")));

        assertThatThrownBy(() -> registry.resolve(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill:dup");
    }

    @Test
    void catalogFindServesExactNameFromSameResolution() {
        CompositeCommandRegistry registry = new CompositeCommandRegistry(List.of(source("skill:one", "skill:two")));

        ResolvedCommandCatalog catalog = registry.resolve(session);

        assertThat(catalog.find("skill:one")).isPresent();
        assertThat(catalog.find("skill:two")).isPresent();
        assertThat(catalog.find("skill:missing")).isEmpty();
    }

    private CommandDefinitionSource source(String... names) {
        return sessionView -> java.util.Arrays.stream(names)
                .map(CompositeCommandRegistryTest.this::resolved)
                .toList();
    }

    private ResolvedCommand resolved(String name) {
        return new ResolvedCommand(
                name,
                CommandKind.SKILL,
                "description",
                true,
                null,
                new ResolvedCommand.Input("optional", true, null, true, "request", null),
                null);
    }
}
