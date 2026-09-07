/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeLifetimeUsageDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.huawei.hicampus.claw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.MyBatisRuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.contributor.ModelCommandContributor;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 组合真实窄服务和 Repository，验证查询、锁内事件、条件更新及独立 Contributor。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/06]
 * @since [br_eCampusCore 26.0.0]
 */
class SessionModelConfigurationServiceTest {
    private final RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);

    private final AgentDirectoryResolver resolver = mock(AgentDirectoryResolver.class);

    private final RuntimeModelManager manager = mock(RuntimeModelManager.class);

    private final RuntimeSessionRepository repository = new MyBatisRuntimeSessionRepository(mapper);

    private final RuntimeEntryCodec codec =
            new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-06T00:00:00Z");

    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);

    private final AtomicInteger ids = new AtomicInteger();

    private final RuntimeEntryIdGenerator idGenerator = () -> "entry-" + ids.incrementAndGet();

    private final SessionModelConfigurationService service =
            new SessionModelConfigurationService(repository, resolver, manager, codec, idGenerator, clock);

    private final List<RuntimeEntryDTO> appended = new ArrayList<>();

    private final AgentDirectorySnapshotDTO snapshot = new AgentDirectorySnapshotDTO(
            "agent", "old", List.of("old", "next"), Path.of("/runtime/agent"), Path.of("/runtime/agent/.campusclaw"));

    @BeforeEach
    void setUp() {
        when(mapper.findLifetimeUsage("session")).thenReturn(new RuntimeLifetimeUsageDTO());
        when(mapper.findSession("session")).thenReturn(session("old", "idle", false, 1L));
        when(mapper.lockSessionForUpdate("session")).thenReturn(session("latest", "idle", true, 4L));
        when(resolver.resolve("agent")).thenReturn(snapshot);
        when(manager.listAvailableModels(snapshot)).thenReturn(List.of("next", "old"));
        Model model = mock(Model.class);
        when(model.id()).thenReturn("next");
        when(model.reasoning()).thenReturn(false);
        when(manager.resolveAvailableModel(snapshot, "next")).thenReturn(model);
        when(mapper.updateSessionModel(eq("session"), eq("next"), anyBoolean(), eq(now)))
                .thenReturn(1);
        when(mapper.lockNextSequence("session")).thenReturn(17L, 18L);
        when(mapper.incrementSequence("session")).thenReturn(1);
        when(mapper.updateActiveLeafAnyState(eq("session"), any())).thenReturn(1);
        when(mapper.insertEntry(any())).thenAnswer(call -> {
            appended.add(call.getArgument(0));
            return 1;
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldQueryRunningSessionWithoutLockingOrWriting(String arguments) {
        when(mapper.findSession("session")).thenReturn(session("old", "running", true, 1L));
        assertThat(service.execute("session", arguments))
                .isEqualTo(new ModelCommandResultDTO("old", List.of("next", "old"), false, null));
        verify(mapper, never()).lockSessionForUpdate(any());
        verify(manager, never()).resolveAvailableModel(any(), any());
        assertThat(ids).hasValue(0);
    }

    @Test
    void shouldKeepEmptyModelListImmutableAndNonNull() {
        when(manager.listAvailableModels(snapshot)).thenReturn(List.of());
        var result = service.execute("session", "");
        assertThat(result.models()).isEmpty();
        assertThatThrownBy(() -> result.models().add("unexpected")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.sourceEventSeq()).isNull();
    }

    @Test
    void shouldBuildBothEventsFromLockedStateAndReturnLastAuthoritativeSequence() {
        var result = service.execute("session", "next");
        assertThat(result).isEqualTo(new ModelCommandResultDTO("next", List.of("next", "old"), true, 18L));
        assertThat(appended)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("session.model.changed", "session.thinking.changed");
        assertThat(codec.toHistoryEvent(appended.getFirst()))
                .containsEntry("previousModelId", "latest")
                .containsEntry("modelId", "next")
                .containsEntry("reason", "requested")
                .containsEntry("entrySeq", 17L);
        assertThat(codec.toHistoryEvent(appended.getLast()))
                .containsEntry("previousThinking", true)
                .containsEntry("thinking", false)
                .containsEntry("reason", "modelCapability")
                .containsEntry("entrySeq", 18L);
        assertThat(appended).extracting(RuntimeEntryDTO::getParentId).containsExactly("prior", "entry-1");
        verify(mapper).updateSessionModel("session", "next", false, now);
        verify(mapper).updateActiveLeafAnyState("session", "entry-2");
    }

    @Test
    void shouldAppendOnlyModelEventWhenLatestThinkingIsOff() {
        when(mapper.lockSessionForUpdate("session")).thenReturn(session("latest", "idle", false, 4L));
        assertThat(service.execute("session", "next").sourceEventSeq()).isEqualTo(17L);
        assertThat(appended).extracting(RuntimeEntryDTO::getType).containsExactly("session.model.changed");
    }

    @Test
    void shouldPreserveThinkingForCapableModel() {
        Model model = mock(Model.class);
        when(model.id()).thenReturn("next");
        when(model.reasoning()).thenReturn(true);
        when(manager.resolveAvailableModel(snapshot, "next")).thenReturn(model);
        assertThat(service.execute("session", "next").changed()).isTrue();
        verify(mapper).updateSessionModel("session", "next", true, now);
        assertThat(appended).extracting(RuntimeEntryDTO::getType).containsExactly("session.model.changed");
    }

    @Test
    void shouldSkipIdenticalModelWithoutChangingThinkingVersionOrSequence() {
        var locked = session("next", "idle", true, 9L);
        when(mapper.lockSessionForUpdate("session")).thenReturn(locked);
        assertThat(service.execute("session", "next").changed()).isFalse();
        assertThat(service.execute("session", "next").sourceEventSeq()).isNull();
        assertThat(locked.getResourceVersion()).isEqualTo(9L);
        assertThat(locked.isThinking()).isTrue();
        assertThat(ids).hasValue(0);
        verify(mapper, never()).updateSessionModel(any(), any(), anyBoolean(), any());
        verify(mapper, never()).lockNextSequence(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "missing"})
    void shouldRecheckStateAfterDiscoveryAndBeforeCreatingEvents(String state) {
        when(mapper.lockSessionForUpdate("session"))
                .thenReturn(state.equals("missing") ? null : session("next", state, false, 4L));
        var expected = state.equals("missing") ? RuntimeErrorCode.SESSION_NOT_FOUND : RuntimeErrorCode.SESSION_BUSY;
        assertThatThrownBy(() -> service.execute("session", "next"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(expected));
        assertThat(ids).hasValue(0);
        verify(mapper, never()).updateSessionModel(any(), any(), anyBoolean(), any());
    }

    @Test
    void shouldKeepConditionalPutVersionCheckInsideTransaction() {
        assertThatThrownBy(() -> service.change(session("old", "idle", false, 1L), "next", 1L))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_VERSION_MISMATCH));
        assertThat(ids).hasValue(0);
        verify(mapper, never()).insertEntry(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "missing"})
    void shouldRejectUnavailableSessionBeforeManagerCalls(String state) {
        when(mapper.findSession("session"))
                .thenReturn(state.equals("missing") ? null : session("old", state, false, 1L));
        var expected = state.equals("missing") ? RuntimeErrorCode.SESSION_NOT_FOUND : RuntimeErrorCode.SESSION_BUSY;
        assertThatThrownBy(() -> service.execute("session", "next"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(expected));
        verifyNoInteractions(resolver, manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_NOT_AVAILABLE", "MANAGER_UNAVAILABLE", "AGENT_NOT_AVAILABLE"})
    void shouldPreserveStableDependencyErrorsWithoutWriting(String code) {
        when(manager.resolveAvailableModel(snapshot, "next"))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.valueOf(code)));
        assertThatThrownBy(() -> service.execute("session", "next"))
                .isInstanceOfSatisfying(
                        RuntimeApiException.class,
                        error -> assertThat(error.errorCode().name()).isEqualTo(code));
        verify(mapper, never()).lockSessionForUpdate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", " next ", "/next", " "})
    void shouldResolveNonEmptyArgumentsAsExactModelIdentifiers(String arguments) {
        when(manager.resolveAvailableModel(snapshot, arguments)).thenCallRealMethod();
        assertThatThrownBy(() -> service.execute("session", arguments))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.MODEL_NOT_AVAILABLE));
        verify(manager).resolveAvailableModel(snapshot, arguments);
        verify(mapper, never()).lockSessionForUpdate(any());
    }

    @Test
    void shouldTranslateStorageFailureWithoutExposingCauseText() {
        when(mapper.insertEntry(any())).thenThrow(new IllegalStateException("private database detail"));
        assertThatThrownBy(() -> service.execute("session", "next"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.COMMAND_EXECUTION_FAILED);
                    assertThat(error.getMessage()).doesNotContain("private database detail");
                });
    }

    @Test
    void shouldWireContributorAndExecuteFromCatalogWithoutOwningRequestState() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RuntimeSessionRepository.class, () -> repository);
            context.registerBean(AgentDirectoryResolver.class, () -> resolver);
            context.registerBean(RuntimeModelManager.class, () -> manager);
            context.registerBean(RuntimeEntryCodec.class, () -> codec);
            context.registerBean(RuntimeEntryIdGenerator.class, () -> idGenerator);
            context.registerBean(Clock.class, () -> clock);
            context.register(SessionModelConfigurationService.class, ModelCommandContributor.class);
            context.refresh();
            var definition = context.getBean(ModelCommandContributor.class).definition();
            var observed = CommandSessionSnapshotDTO.from(session("old", "running", false, 1L));
            var descriptor = definition.describe(observed);
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.input().available()).isFalse();
            assertThat(descriptor.input().unavailableCode()).isEqualTo("SESSION_BUSY");
            assertThat(descriptor.input().mode()).isEqualTo("optional");
            assertThat(descriptor.input().acceptsFiles()).isFalse();
            var catalog = new ResolvedCommandCatalog(observed, List.of(descriptor), Map.of("model", definition));
            var result = definition
                    .handler()
                    .execute(new CommandExecutionContext(Locale.US, catalog), "")
                    .toCompletableFuture()
                    .get();
            assertThat(result).isEqualTo(new ModelCommandResultDTO("old", List.of("next", "old"), false, null));
        }
    }

    private RuntimeSessionDTO session(String modelId, String state, boolean thinking, long version) {
        var session = new RuntimeSessionDTO();
        session.setId("session");
        session.setAgentId("agent");
        session.setModelId(modelId);
        session.setState(state);
        session.setThinking(thinking);
        session.setResourceVersion(version);
        session.setActiveLeafId("prior");
        session.setCreatedAt(now.minusDays(1));
        session.setUpdatedAt(now.minusHours(1));
        return session;
    }
}
