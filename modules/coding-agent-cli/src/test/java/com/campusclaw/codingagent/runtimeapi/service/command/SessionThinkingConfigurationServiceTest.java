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
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ThinkingCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.MyBatisRuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.ThinkingCommandContributor;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionConfigurationService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionResponseAssembler;
import com.campusclaw.codingagent.runtimeapi.session.SessionEtagFactory;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 组合真实 Service、Repository 和配置入口验证 Thinking 的锁内能力及领域事件语义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class SessionThinkingConfigurationServiceTest {
    private final RuntimeSessionMapper mapper = mock(RuntimeSessionMapper.class);

    private final AgentDirectoryResolver resolver = mock(AgentDirectoryResolver.class);

    private final RuntimeModelManager manager = mock(RuntimeModelManager.class);

    private final RuntimeSessionRepository repository = new MyBatisRuntimeSessionRepository(mapper);

    private final RuntimeEntryCodec codec =
            new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-07T00:00:00Z");

    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);

    private final AtomicInteger ids = new AtomicInteger();

    private final RuntimeEntryIdGenerator idGenerator = () -> "entry-" + ids.incrementAndGet();

    private final SessionThinkingConfigurationService service =
            new SessionThinkingConfigurationService(repository, resolver, manager, codec, idGenerator, clock);

    private final List<RuntimeEntryDTO> appended = new ArrayList<>();

    private final AgentDirectorySnapshotDTO snapshot = new AgentDirectorySnapshotDTO(
            "agent", "old", List.of("old", "latest"), Path.of("/runtime/agent"), Path.of("/runtime/agent/.campusclaw"));

    @BeforeEach
    void setUp() {
        when(mapper.findSession("session")).thenReturn(session("old", "idle", false, 1L));
        when(mapper.lockSessionForUpdate("session")).thenReturn(session("latest", "idle", false, 4L));
        when(resolver.resolve("agent")).thenReturn(snapshot);
        allowThinking("old", true);
        allowThinking("latest", true);
        when(mapper.updateSessionThinking(eq("session"), anyBoolean(), eq(now))).thenReturn(1);
        when(mapper.lockNextSequence("session")).thenReturn(17L);
        when(mapper.incrementSequence("session")).thenReturn(1);
        when(mapper.updateActiveLeafAnyState(eq("session"), any())).thenReturn(1);
        when(mapper.insertEntry(any())).thenAnswer(call -> {
            appended.add(call.getArgument(0));
            return 1;
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldQueryPersistedRunningStateWithoutAgentOrModelLookup(String arguments) {
        when(mapper.findSession("session")).thenReturn(session("old", "running", true, 1L));
        assertThat(service.execute("session", arguments)).isEqualTo(new ThinkingCommandResultDTO(true, false, null));
        verifyNoInteractions(resolver, manager);
        verify(mapper, never()).lockSessionForUpdate(any());
        assertThat(ids).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "ON", "Off", " on ", "off\n", " ", "1", "/on"})
    void shouldRejectAnythingExceptExactLowercaseSwitchesBeforePersistence(String arguments) {
        assertThatThrownBy(() -> service.execute("session", arguments))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST));
        verifyNoInteractions(mapper, resolver, manager);
    }

    @Test
    void shouldEnableAgainstLockedModelAndAppendOneAuthoritativeEntry() {
        var locked = session("latest", "idle", false, 4L);
        when(mapper.lockSessionForUpdate("session")).thenReturn(locked);
        assertThat(service.execute("session", "on")).isEqualTo(new ThinkingCommandResultDTO(true, true, 17L));
        verify(manager).resolveModel(snapshot, "latest");
        verify(resolver).resolve("agent");
        assertThat(appended).extracting(RuntimeEntryDTO::getType).containsExactly("session.thinking.changed");
        assertThat(codec.toHistoryEvent(appended.getFirst()))
                .containsEntry("previousThinking", false)
                .containsEntry("thinking", true)
                .containsEntry("reason", "requested")
                .containsEntry("entrySeq", 17L);
        assertThat(appended.getFirst().getParentId()).isEqualTo("prior");
        assertThat(locked.getResourceVersion()).isEqualTo(5L);
        assertThat(locked.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void shouldDisableUsingLatestPreviousValueWithoutRequiringModelAvailability() {
        when(mapper.lockSessionForUpdate("session")).thenReturn(session("latest", "idle", true, 4L));
        assertThat(service.execute("session", "off")).isEqualTo(new ThinkingCommandResultDTO(false, true, 17L));
        assertThat(codec.toHistoryEvent(appended.getFirst()))
                .containsEntry("previousThinking", true)
                .containsEntry("thinking", false);
        verifyNoInteractions(resolver, manager);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSkipLockedSameValueWithoutIdsVersionTimestampOrHistory(boolean thinking) {
        var locked = session("latest", "idle", thinking, 4L);
        when(mapper.lockSessionForUpdate("session")).thenReturn(locked);
        assertThat(service.execute("session", thinking ? "on" : "off"))
                .isEqualTo(new ThinkingCommandResultDTO(thinking, false, null));
        assertThat(locked.getResourceVersion()).isEqualTo(4L);
        assertThat(locked.getUpdatedAt()).isEqualTo(now.minusHours(1));
        assertThat(locked.getActiveLeafId()).isEqualTo("prior");
        assertThat(ids).hasValue(0);
        verify(mapper, never()).lockNextSequence(any());
        verify(mapper, never()).updateSessionThinking(any(), anyBoolean(), any());
    }

    @Test
    void shouldRejectModelThatLostCapabilityBeforeLockEvenWhenThinkingAlreadyOn() {
        allowThinking("latest", false);
        when(mapper.lockSessionForUpdate("session")).thenReturn(session("latest", "idle", true, 4L));
        assertThatThrownBy(() -> service.execute("session", "on"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.THINKING_NOT_SUPPORTED));
        assertThat(ids).hasValue(0);
        verify(mapper, never()).updateSessionThinking(any(), anyBoolean(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "running"})
    void shouldRecheckSessionUnderLockBeforeCapabilityAndEntryFactory(String state) {
        when(mapper.lockSessionForUpdate("session"))
                .thenReturn(state.equals("missing") ? null : session("latest", state, true, 4L));
        var expected = state.equals("missing") ? RuntimeErrorCode.SESSION_NOT_FOUND : RuntimeErrorCode.SESSION_BUSY;
        assertThatThrownBy(() -> service.execute("session", "on"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(expected));
        verify(manager, never()).resolveModel(snapshot, "latest");
        assertThat(ids).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "running"})
    void shouldRejectMissingOrBusyBeforeResolvingAgent(String state) {
        when(mapper.findSession("session"))
                .thenReturn(state.equals("missing") ? null : session("old", state, true, 1L));
        var expected = state.equals("missing") ? RuntimeErrorCode.SESSION_NOT_FOUND : RuntimeErrorCode.SESSION_BUSY;
        assertThatThrownBy(() -> service.execute("session", "on"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(expected));
        verifyNoInteractions(resolver, manager);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"MANAGER_UNAVAILABLE", "MODEL_NOT_AVAILABLE", "AGENT_NOT_AVAILABLE", "AGENT_MODEL_NOT_CONFIGURED"
            })
    void shouldMapOnlyCommandModelErrorAndPreserveOtherStableErrors(String code) {
        when(manager.resolveModel(snapshot, "old")).thenThrow(new RuntimeApiException(RuntimeErrorCode.valueOf(code)));
        var expected = code.equals("AGENT_MODEL_NOT_CONFIGURED") ? "MODEL_NOT_AVAILABLE" : code;
        assertThatThrownBy(() -> service.execute("session", "on"))
                .isInstanceOfSatisfying(
                        RuntimeApiException.class,
                        error -> assertThat(error.errorCode().name()).isEqualTo(expected));
        verify(mapper, never()).lockSessionForUpdate(any());
    }

    @Test
    void shouldPreserveConditionalPutEtagAndRejectVersionRaceBeforeAnyWrite() {
        var etags = new SessionEtagFactory();
        var api = new RuntimeSessionConfigurationService(
                repository,
                new SessionModelConfigurationService(repository, resolver, manager, codec, idGenerator, clock),
                service,
                etags,
                new RuntimeSessionResponseAssembler(etags));
        var request = new ChangeThinkingRequestVO();
        request.readThinking(BooleanNode.TRUE);
        assertThatThrownBy(() -> api.changeThinking("session", null, request))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.IF_MATCH_REQUIRED));
        assertThatThrownBy(() -> api.changeThinking("session", etags.create("session", 1L), request))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_VERSION_MISMATCH));
        assertThat(ids).hasValue(0);
        when(mapper.findSession("session")).thenReturn(session("latest", "idle", false, 4L));
        var changed = api.changeThinking("session", etags.create("session", 4L), request);
        assertThat(changed.resource().isThinking()).isTrue();
        assertThat(changed.etag()).isEqualTo(etags.create("session", 5L));
    }

    @Test
    void shouldTranslateUnexpectedStorageFailureWithoutPrivateDetails() {
        when(mapper.insertEntry(any())).thenThrow(new IllegalStateException("private database detail"));
        assertThatThrownBy(() -> service.execute("session", "on"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.COMMAND_EXECUTION_FAILED);
                    assertThat(error.getMessage()).doesNotContain("private database detail");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void shouldWireActualContributorAndHandleCatalogQueryWithoutDiscoverySideEffects() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RuntimeSessionRepository.class, () -> repository);
            context.registerBean(AgentDirectoryResolver.class, () -> resolver);
            context.registerBean(RuntimeModelManager.class, () -> manager);
            context.registerBean(RuntimeEntryCodec.class, () -> codec);
            context.registerBean(RuntimeEntryIdGenerator.class, () -> idGenerator);
            context.registerBean(Clock.class, () -> clock);
            context.register(SessionThinkingConfigurationService.class, ThinkingCommandContributor.class);
            context.refresh();
            var definition = context.getBean(ThinkingCommandContributor.class).definition();
            var observed = CommandSessionSnapshotDTO.from(session("old", "running", false, 1L));
            var descriptor = definition.describe(observed);
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.input().available()).isFalse();
            assertThat(descriptor.input().unavailableCode()).isEqualTo("SESSION_BUSY");
            assertThat(descriptor.input().mode()).isEqualTo("optional");
            assertThat(descriptor.input().acceptsFiles()).isFalse();
            assertThat(descriptor.input().suggestions()).containsExactly("on", "off");
            assertThat(descriptor.input().placeholder()).isEqualTo("on|off");
            verifyNoInteractions(mapper, resolver, manager);
            var catalog = new ResolvedCommandCatalog(observed, List.of(descriptor), Map.of("thinking", definition));
            assertThat(definition
                            .handler()
                            .execute(new CommandExecutionContext(Locale.US, catalog), "")
                            .toCompletableFuture()
                            .get())
                    .isEqualTo(new ThinkingCommandResultDTO(false, false, null));
        }
    }

    private void allowThinking(String modelId, boolean reasoning) {
        Model model = mock(Model.class);
        when(model.reasoning()).thenReturn(reasoning);
        when(manager.resolveModel(snapshot, modelId)).thenReturn(model);
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
