/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

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

import com.campusclaw.ai.types.Model;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeLifetimeUsageDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ModelCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.MyBatisRuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.ModelCommandContributor;
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

    private final RuntimeCommittedEventFactory committedEventFactory = new RuntimeCommittedEventFactory(
            new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-06T00:00:00Z");

    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);

    private final AtomicInteger ids = new AtomicInteger();

    private final RuntimeEntryIdGenerator idGenerator = () -> "entry-" + ids.incrementAndGet();

    private final SessionModelConfigurationService service = new SessionModelConfigurationService(
            repository, resolver, manager, codec, committedEventFactory, idGenerator, clock);

    private final List<RuntimeEntryDTO> appended = new ArrayList<>();

    private final List<CommittedEventDTO> committed = new ArrayList<>();

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
        when(mapper.lockNextSequence("session")).thenReturn(17L, 18L, 19L, 20L);
        when(mapper.incrementSequence("session")).thenReturn(1);
        when(mapper.updateActiveLeafAnyState(eq("session"), any())).thenReturn(1);
        when(mapper.insertCommittedEvent(any())).thenAnswer(call -> {
            committed.add(call.getArgument(0));
            return 1;
        });
        when(mapper.insertCommittedEventProjection(any(), any(), any(Integer.class)))
                .thenReturn(1);
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
                .isEqualTo(new ModelCommandResultDTO("old", List.of("next", "old")));
        verify(mapper, never()).lockSessionForUpdate(any());
        verify(manager, never()).resolveAvailableModel(any(), any());
        assertThat(ids).hasValue(0);
    }

    @Test
    void shouldKeepEmptyModelListImmutableAndNonNull() {
        when(manager.listAvailableModels(snapshot)).thenReturn(List.of());
        var result = service.query("session");
        assertThat(result.models()).isEmpty();
        assertThatThrownBy(() -> result.models().add("unexpected")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldBuildBothEventsFromLockedStateAndReturnLastAuthoritativeSequence() {
        var result = executeChange("next");
        assertThat(result.changed()).isTrue();
        assertThat(result.sourceEventSeq()).isEqualTo(19L);
        assertThat(result.session().getModelId()).isEqualTo("next");
        assertThat(result.session().getResourceVersion()).isEqualTo(5L);
        assertThat(result.session().getUpdatedAt()).isEqualTo(now);
        assertThat(result.session().getActiveLeafId()).isEqualTo("entry-2");
        verify(mapper).findSession("session");
        verify(manager, never()).listAvailableModels(any());
        assertThat(appended)
                .extracting(RuntimeEntryDTO::getType)
                .containsExactly("session.model.changed", "session.thinking.changed");
        assertThat(appended).extracting(RuntimeEntryDTO::getParentId).containsExactly("prior", "entry-1");
        assertThat(committed)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("session.model_changed", "session.thinking_changed");
        assertThat(committed).extracting(CommittedEventDTO::getEventSeq).containsExactly(18L, 20L);
        assertThat(committed).extracting(CommittedEventDTO::getEventId).containsExactly("entry-1", "entry-2");
        verify(mapper).insertCommittedEventProjection("session", "entry-1", 1);
        verify(mapper).insertCommittedEventProjection("session", "entry-2", 1);
        verify(mapper).updateSessionModel("session", "next", false, now);
        verify(mapper).updateActiveLeafAnyState("session", "entry-2");
    }

    @Test
    void shouldAppendOnlyModelEventWhenLatestThinkingIsOff() {
        when(mapper.lockSessionForUpdate("session")).thenReturn(session("latest", "idle", false, 4L));
        assertThat(executeChange("next").sourceEventSeq()).isEqualTo(17L);
        assertThat(appended).extracting(RuntimeEntryDTO::getType).containsExactly("session.model.changed");
    }

    @Test
    void shouldPreserveThinkingForCapableModel() {
        Model model = mock(Model.class);
        when(model.id()).thenReturn("next");
        when(model.reasoning()).thenReturn(true);
        when(manager.resolveAvailableModel(snapshot, "next")).thenReturn(model);
        assertThat(executeChange("next").changed()).isTrue();
        verify(mapper).updateSessionModel("session", "next", true, now);
        assertThat(appended).extracting(RuntimeEntryDTO::getType).containsExactly("session.model.changed");
    }

    @Test
    void shouldSkipIdenticalModelWithoutChangingThinkingVersionOrSequence() {
        var locked = session("next", "idle", true, 9L);
        when(mapper.lockSessionForUpdate("session")).thenReturn(locked);
        var result = executeChange("next");
        assertThat(result.changed()).isFalse();
        assertThat(result.sourceEventSeq()).isNull();
        assertThat(result.session()).isSameAs(locked);
        assertThat(result.session().getUpdatedAt()).isEqualTo(now.minusHours(1));
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
            context.registerBean(RuntimeCommittedEventFactory.class, () -> committedEventFactory);
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
            assertThat(result).isEqualTo(new ModelCommandResultDTO("old", List.of("next", "old")));
        }
    }

    private SessionCommandResultDTO executeChange(String modelId) {
        var result = service.execute("session", modelId);
        assertThat(result).isInstanceOf(SessionCommandResultDTO.class);
        return (SessionCommandResultDTO) result;
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
