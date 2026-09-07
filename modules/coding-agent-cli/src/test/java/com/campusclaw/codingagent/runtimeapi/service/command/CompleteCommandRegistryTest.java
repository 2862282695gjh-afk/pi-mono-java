/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.command.definition.DisplayCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.source.CommandDefinitionSource;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CompleteCommandRegistryTest {
    @Test
    void resolveComplete_shouldUseOneExplicitSnapshot_andKeepDefinitionIdentity() {
        var session = session();
        var prepared = prepared("agent", "agent");
        var source = mock(CommandDefinitionSource.class);
        var descriptor = new ResolvedCommandDTO("skill:alpha", CommandKind.SKILL, "alpha", true, null, null, null);
        var definition = new DisplayCommandDefinition(descriptor);
        when(source.kind()).thenReturn(CommandKind.SKILL);
        doReturn(List.of(definition)).when(source).definitions(session, prepared);
        var catalog = new CompositeCommandRegistry(List.of(source)).resolveComplete(session, prepared);
        assertThat(catalog.find("skill:alpha")).containsSame(descriptor);
        assertThat(catalog.findDefinition("skill:alpha")).containsSame(definition);
        assertThat(catalog.list()).containsExactly(descriptor);
        verify(source).definitions(session, prepared);
        verify(source, never()).definitions(any(RuntimeSessionDTO.class));
        verify(source, never()).list(any());
        session.setAgentId("changed");
        assertThat(catalog.session().agentId()).isEqualTo("agent");
    }

    @ParameterizedTest
    @CsvSource({"other,agent", "agent,other"})
    void resolveComplete_shouldRejectCrossAgentSnapshot_beforeInvokingSources(String agentId, String metadataId) {
        var source = mock(CommandDefinitionSource.class);
        var registry = new CompositeCommandRegistry(List.of(source));
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.resolveComplete(session(), prepared(agentId, metadataId)));
        assertThat(failure).hasMessageContaining("does not match Session Agent");
        verifyNoInteractions(source);
    }

    @Test
    void resolveComplete_shouldRejectIncompleteSnapshot_beforeInvokingSources() {
        var source = mock(CommandDefinitionSource.class);
        var registry = new CompositeCommandRegistry(List.of(source));
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.resolveComplete(
                        session(), new PreparedAgentRuntime("agent", Path.of("/unused"), null, List.of())));
        assertThat(failure).hasMessageContaining("does not match Session Agent");
        verifyNoInteractions(source);
    }

    @ParameterizedTest
    @CsvSource({"SKILL,duplicate", "BUILTIN,kind"})
    void resolveComplete_shouldRetainDuplicateAndKindChecks(String kind, String reason) {
        var session = session();
        var prepared = prepared("agent", "agent");
        var source = mock(CommandDefinitionSource.class);
        when(source.kind()).thenReturn(CommandKind.valueOf(kind));
        var descriptor = new ResolvedCommandDTO("skill:alpha", CommandKind.SKILL, "alpha", true, null, null, null);
        var definition = new DisplayCommandDefinition(descriptor);
        doReturn(List.of(definition, definition)).when(source).definitions(session, prepared);
        var failure = assertThrows(IllegalStateException.class, () -> new CompositeCommandRegistry(List.of(source))
                .resolveComplete(session, prepared));
        assertThat(failure)
                .hasMessageContaining(
                        reason.equals("duplicate") ? "Duplicate command name" : "differs from its source");
    }

    private RuntimeSessionDTO session() {
        var value = new RuntimeSessionDTO();
        value.setAgentId("agent");
        value.setState("idle");
        return value;
    }

    private PreparedAgentRuntime prepared(String agentId, String metadataId) {
        var metadata = new AgentRuntime(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                true,
                metadataId,
                "agent",
                "prompt",
                List.of(),
                "v1");
        return new PreparedAgentRuntime(agentId, Path.of("/unused"), metadata, List.of());
    }
}
