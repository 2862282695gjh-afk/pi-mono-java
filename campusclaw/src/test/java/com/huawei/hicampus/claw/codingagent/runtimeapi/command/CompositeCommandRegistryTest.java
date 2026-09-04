/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

import org.junit.jupiter.api.Test;

/**
 * 验证来源合并、重名检测、只解析一次和不可变清单。
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
                .extracting(ResolvedCommandDTO::name)
                .containsExactly("skill:alpha", "skill:middle", "skill:zeta");
    }

    @Test
    void resolveFailsFastOnDuplicateNames() {
        CompositeCommandRegistry registry =
                new CompositeCommandRegistry(List.of(source("skill:dup"), source("skill:dup")));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> registry.resolve(session));
        assertThat(failure).hasMessageContaining("skill:dup");
    }

    @Test
    void catalogFindServesExactNameFromSameResolution() {
        CommandDefinitionSource source = mock(CommandDefinitionSource.class);
        when(source.list(session)).thenReturn(List.of(resolved("skill:one"), resolved("skill:two")));
        CompositeCommandRegistry registry = new CompositeCommandRegistry(List.of(source));

        ResolvedCommandCatalog catalog = registry.resolve(session);

        assertThat(catalog.find("skill:one")).isPresent();
        assertThat(catalog.find("skill:two")).isPresent();
        assertThat(catalog.find("skill:missing")).isEmpty();
        verify(source).list(session);
    }

    @Test
    void suggestionsAreCopiedAndCannotBeChanged() {
        List<String> suggestions = new ArrayList<>(List.of("first"));
        ResolvedCommandDTO.InputDTO input =
                new ResolvedCommandDTO.InputDTO("optional", true, null, false, "", suggestions);
        suggestions.add("later");

        assertThat(input.suggestions()).containsExactly("first");
        assertThatThrownBy(() -> input.suggestions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private CommandDefinitionSource source(String... names) {
        return sessionView -> java.util.Arrays.stream(names)
                .map(CompositeCommandRegistryTest.this::resolved)
                .toList();
    }

    private ResolvedCommandDTO resolved(String name) {
        return new ResolvedCommandDTO(
                name,
                CommandKind.SKILL,
                "description",
                true,
                null,
                new ResolvedCommandDTO.InputDTO("optional", true, null, true, "request", List.of()),
                null);
    }
}
