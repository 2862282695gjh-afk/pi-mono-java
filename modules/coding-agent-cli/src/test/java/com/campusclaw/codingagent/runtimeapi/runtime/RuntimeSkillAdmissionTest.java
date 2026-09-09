/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.AgentRuntimeManager;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.service.command.skill.SkillCommandAdmission;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.compaction.SessionCompactor;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.campusclaw.codingagent.tool.builtin.ConfiguredToolAssembler;
import com.campusclaw.codingagent.tool.cron.AgentScopedCronToolFactory;
import com.campusclaw.codingagent.tool.mate.MateToolsetFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class RuntimeSkillAdmissionTest {
    @Test
    void testValidatesTheActualFactorySnapshotAndReleasesRejectedCapacity(@TempDir Path directory) {
        Fixture fixture = new Fixture(directory);
        PreparedAgentRuntime unbound = fixture.runtime(List.of());
        PreparedAgentRuntime bound = fixture.runtime(List.of(skill()));
        when(fixture.manager.prepare("agent-a")).thenReturn(unbound, bound);

        RuntimeApiException failure = assertThrows(RuntimeApiException.class, fixture::registerSkill);

        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.COMMAND_NOT_FOUND);
        assertThat(fixture.registry.find("session-a")).isEmpty();
        verifyNoInteractions(fixture.assembler, fixture.promptLoader, fixture.mateProvider);
        RuntimeSessionHolder accepted = fixture.registerSkill();
        assertThat(fixture.registry.find("session-a")).containsSame(accepted);
        verify(fixture.manager, times(2)).prepare("agent-a");
        fixture.registry.complete(accepted, fixture.execution);
        assertThat(fixture.registry.find("session-a")).isEmpty();
    }

    @Test
    void testDoesNotReusePreviousAdmissionForAnotherExecution(@TempDir Path directory) {
        Fixture fixture = new Fixture(directory);
        when(fixture.manager.prepare("agent-a"))
                .thenReturn(fixture.runtime(List.of(skill())), fixture.runtime(List.of()));
        RuntimeSessionHolder accepted = fixture.registerSkill();
        fixture.registry.complete(accepted, fixture.execution);

        RuntimeApiException failure = assertThrows(RuntimeApiException.class, fixture::registerSkill);

        assertThat(failure.errorCode()).isEqualTo(RuntimeErrorCode.COMMAND_NOT_FOUND);
        assertThat(fixture.registry.find("session-a")).isEmpty();
        verify(fixture.manager, times(2)).prepare("agent-a");
        verify(fixture.assembler).assemble(any(), any());
    }

    @Test
    void testOrdinaryRegistrationStillAllowsAnAgentWithoutSkills(@TempDir Path directory) {
        Fixture fixture = new Fixture(directory);
        when(fixture.manager.prepare("agent-a")).thenReturn(fixture.runtime(List.of()));

        RuntimeSessionHolder accepted = fixture.registry.register(
                "session-a",
                fixture.snapshot,
                fixture.model,
                false,
                List.of(),
                fixture.execution,
                MateCredentials.empty());

        assertThat(fixture.registry.find("session-a")).containsSame(accepted);
        fixture.registry.complete(accepted, fixture.execution);
        assertThat(fixture.registry.find("session-a")).isEmpty();
    }

    private static SkillInfo skill() {
        return new SkillInfo("pdf", "skill-1", "v1", "description", null, "body", null, null, null, null);
    }

    private static final class Fixture {
        private final AgentRuntimeManager manager = mock(AgentRuntimeManager.class);

        private final ConfiguredToolAssembler assembler = mock(ConfiguredToolAssembler.class);

        private final RuntimeAgentPromptLoader promptLoader = mock(RuntimeAgentPromptLoader.class);

        private final ObjectProvider<MateToolsetFactory> mateProvider = mock(ObjectProvider.class);

        private final RuntimeActiveExecution execution = mock(RuntimeActiveExecution.class);

        private final Model model = mock(Model.class);

        private final Path directory;

        private final AgentDirectorySnapshotDTO snapshot;

        private final RuntimeSessionEngineRegistry registry;

        private Fixture(Path directory) {
            this.directory = directory;
            snapshot = new AgentDirectorySnapshotDTO(
                    "agent-a", "model-a", List.of("model-a"), directory, directory.resolve(".campusclaw"));
            AgentSessionFactory sessionFactory = new AgentSessionFactory(
                    mock(CampusClawAiService.class),
                    manager,
                    assembler,
                    mateProvider,
                    promptLoader,
                    mock(SessionCompactor.class));
            RuntimeExecutionProperties properties = new RuntimeExecutionProperties();
            properties.setMaxActive(1);
            registry = new RuntimeSessionEngineRegistry(
                    sessionFactory,
                    mock(SubagentExecutionService.class),
                    mock(AgentScopedCronToolFactory.class),
                    properties);
            when(model.id()).thenReturn("model-a");
            when(model.provider()).thenReturn(Provider.OPENAI);
            when(assembler.assemble(any(), any())).thenReturn(List.of());
            when(promptLoader.load(any())).thenReturn("System");
        }

        private RuntimeSessionHolder registerSkill() {
            return registry.register(
                    "session-a",
                    snapshot,
                    model,
                    false,
                    List.of(),
                    execution,
                    MateCredentials.empty(),
                    new SkillCommandAdmission("agent-a", "pdf"));
        }

        private PreparedAgentRuntime runtime(List<SkillInfo> skills) {
            AgentRuntime metadata = new AgentRuntime(
                    List.of("model-a"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "Agent",
                    true,
                    "agent-a",
                    "agent",
                    "System",
                    List.of(),
                    "v1");
            return new PreparedAgentRuntime("agent-a", directory, metadata, skills);
        }
    }
}
