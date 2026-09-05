/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeProperties;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillReference;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.SkillsCommandResultDTO.SkillDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.SkillsCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly.BoundSkillQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证 Skills 只读完整本地绑定快照，不刷新、不过期复用且不泄露绑定详情。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
class BoundSkillQueryServiceTest {
    private static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String SKILL_ID = "skill-11111111111111111111111111111111";

    @TempDir
    Path directory;

    private final MateServiceClient client = mock(MateServiceClient.class);

    private AgentRuntimeManager manager;

    private BoundSkillQueryService service;

    @BeforeEach
    void setUp() {
        manager = new AgentRuntimeManager(
                new AgentRuntimeProperties(directory, Duration.ofSeconds(1L), Duration.ofSeconds(2L)),
                client,
                new ObjectMapper());
        service = new BoundSkillQueryService(manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void shouldRejectMissingSnapshotWithoutCallingMate(String state) {
        assertThatThrownBy(() -> execute(state, "")).isInstanceOfSatisfying(RuntimeApiException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
            assertThat(error.status().value()).isEqualTo(422);
        });
        verifyNoInteractions(client);
        assertThat(Files.exists(directory.resolve(AGENT_ID))).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldReturnEmptyListForCompleteEmptyBindingSnapshot(String arguments) {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime(false));
        manager.prepare(AGENT_ID);
        clearInvocations(client);

        assertThat(execute("running", arguments).skills()).isEmpty();
        assertThat(execute("idle", arguments).skills()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void shouldReadLatestPublishedBindingsAndKeepPreviousResultImmutable() {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime(true));
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill("alpha", "First description"));
        manager.prepare(AGENT_ID);
        clearInvocations(client);
        SkillsCommandResultDTO first = execute("idle", "");
        assertThat(first.skills()).containsExactly(new SkillDTO("alpha", "First description"));
        verifyNoInteractions(client);

        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill("beta", "Latest description"));
        manager.refresh(AGENT_ID);
        clearInvocations(client);
        SkillsCommandResultDTO latest = execute("running", "");

        assertThat(latest.skills()).containsExactly(new SkillDTO("beta", "Latest description"));
        assertThat(first.skills()).containsExactly(new SkillDTO("alpha", "First description"));
        verifyNoInteractions(client);
    }

    @Test
    void shouldRejectIncompleteSnapshotWithoutServingPreviouslyReadSkills() throws Exception {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime(true));
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill("alpha", "Description"));
        PreparedAgentRuntime prepared = manager.prepare(AGENT_ID);
        assertThat(execute("idle", "").skills()).containsExactly(new SkillDTO("alpha", "Description"));
        Files.delete(prepared.agentRoot().resolve(".campusclaw/SYSTEM.md"));
        clearInvocations(client);

        assertThatThrownBy(() -> execute("running", ""))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE));
        verifyNoInteractions(client);
    }

    @Test
    void shouldSortAndReduceSkillInfoToNameAndDescription() {
        AgentRuntimeManager cached = mock(AgentRuntimeManager.class);
        when(cached.prepareCached(AGENT_ID))
                .thenReturn(new PreparedAgentRuntime(
                        AGENT_ID, directory, runtime(true), List.of(skill("zebra", "Z"), skill("alpha", "A"))));
        SkillsCommandResultDTO result = new BoundSkillQueryService(cached).query(session("running"), "");

        assertThat(result.skills()).containsExactly(new SkillDTO("alpha", "A"), new SkillDTO("zebra", "Z"));
        assertThat(new ObjectMapper().valueToTree(result).toString())
                .isEqualTo(
                        "{\"skills\":[{\"name\":\"alpha\",\"description\":\"A\"},{\"name\":\"zebra\",\"description\":\"Z\"}]}");
        verify(cached).prepareCached(AGENT_ID);
        verifyNoMoreInteractions(cached);
    }

    @Test
    void shouldDefensivelyCopySkillResultWithoutValidatingInputInDto() {
        List<SkillDTO> skills = new ArrayList<>(List.of(new SkillDTO("alpha", "A")));
        SkillsCommandResultDTO result = new SkillsCommandResultDTO(skills);
        skills.clear();

        assertThat(result.skills()).containsExactly(new SkillDTO("alpha", "A"));
        assertThatThrownBy(() -> result.skills().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private SkillsCommandResultDTO execute(String state, String arguments) {
        CommandExecutionContext context = new CommandExecutionContext(
                Locale.US, new ResolvedCommandCatalog(session(state), List.of(), java.util.Map.of()));
        return (SkillsCommandResultDTO) new SkillsCommandContributor(service)
                .definition()
                .handler()
                .execute(context, arguments)
                .toCompletableFuture()
                .join();
    }

    private CommandSessionSnapshotDTO session(String state) {
        return new CommandSessionSnapshotDTO("session-id", AGENT_ID, state, "provider/model", false, 1L);
    }

    private AgentRuntime runtime(boolean withSkills) {
        return new AgentRuntime(
                List.of("provider/model"),
                withSkills ? List.of(new SkillReference(SKILL_ID, "1.0.0")) : List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Agent",
                true,
                AGENT_ID,
                "agent-a",
                "prompt",
                List.of(),
                "1.0.0");
    }

    private SkillInfo skill(String name, String description) {
        return new SkillInfo(
                name,
                SKILL_ID,
                "1.0.0",
                description,
                "private use cases",
                "---\nname: " + name + "\ndescription: " + description + "\n---\nPrivate body.\n",
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
