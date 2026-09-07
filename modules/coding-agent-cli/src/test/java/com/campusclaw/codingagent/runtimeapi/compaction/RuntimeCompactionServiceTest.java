/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.campusclaw.agent.Agent;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.campusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.campusclaw.codingagent.runtimeapi.persistence.CompactionAcceptanceStatus;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeExecutionProperties;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.session.AgentSessionFactory;
import com.campusclaw.codingagent.session.ManagedAgentSession;
import com.campusclaw.codingagent.session.ManagedAgentSessionRequest;
import com.campusclaw.codingagent.tool.agent.SubagentExecutionService;
import com.campusclaw.codingagent.tool.cron.AgentScopedCronToolFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * 验证压缩准备与交接，使用真实注册表及 Codec，数据库竞争另由 openGauss 测试证明。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeCompactionServiceTest {
    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final AgentDirectoryResolver directories = mock(AgentDirectoryResolver.class);

    private final RuntimeModelManager models = mock(RuntimeModelManager.class);

    private final RuntimeCompactionCoordinator coordinator = mock(RuntimeCompactionCoordinator.class);

    private final RuntimeEntryIdGenerator ids = mock(RuntimeEntryIdGenerator.class);

    private final AgentSessionFactory factory = mock(AgentSessionFactory.class);

    private final ManagedAgentSession managed = mock(ManagedAgentSession.class);

    private final Agent agent = mock(Agent.class);

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-07T00:00:00Z");

    private final RuntimeEntryCodec codec =
            new RuntimeEntryCodec(new ObjectMapper(), new RuntimeMessageSourceConfiguration().messageSource());

    private final RuntimeSessionDTO session = new RuntimeSessionDTO();

    private final AgentDirectorySnapshotDTO directory = new AgentDirectorySnapshotDTO(
            "agent", "model", List.of("model"), Path.of("/agent"), Path.of("/agent/.campusclaw"));

    private final RuntimeSessionEngineRegistry registry;

    private final RuntimeCompactionService service;

    RuntimeCompactionServiceTest() {
        var properties = new RuntimeExecutionProperties();
        properties.setMaxActive(1);
        registry = new RuntimeSessionEngineRegistry(
                factory, mock(SubagentExecutionService.class), mock(AgentScopedCronToolFactory.class), properties);
        Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
        service =
                new RuntimeCompactionService(repository, registry, directories, models, codec, coordinator, ids, clock);
        session.setId("session");
        session.setAgentId("agent");
        session.setState("idle");
        session.setModelId("model");
        session.setThinking(true);
        observe(List.of(codec.userEntry("session", "entry", "hello", List.of(), now)));
        when(directories.resolve("agent")).thenReturn(directory);
        when(models.resolveAvailableModel(directory, "model")).thenReturn(mock(Model.class));
        when(factory.create(any())).thenReturn(managed);
        when(managed.agent()).thenReturn(agent);
        when(ids.nextId()).thenReturn("internal-usage");
        when(repository.acceptCompaction(session, now)).thenReturn(CompactionAcceptanceStatus.ACCEPTED);
        when(coordinator.start(any(), any(), any())).thenAnswer(call -> {
            RuntimeCompactionExecution execution = call.getArgument(1);
            return execution.result();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "configuration"})
    void shouldObserveEmptyContextWithoutPreparingAgentOrAllocatingResources(String kind) {
        observe(
                kind.equals("empty")
                        ? List.of()
                        : List.of(codec.modelChangedEntry("session", "configuration", null, "model", "user", now)));
        assertThat(service.compact("session", null, Locale.CHINA)
                        .toCompletableFuture()
                        .join())
                .isEqualTo(new RuntimeCompactionResultDTO(false, null));
        verifyNoInteractions(directories, models, factory, ids, coordinator);
        verify(repository, never()).acceptCompaction(any(), any());
        verify(repository, never()).finishExecution(any(), any());
        assertThat(registry.find("session")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "running"})
    void shouldRejectBeforeEmptyHistoryAndResourcePreparation(String kind) {
        if (kind.equals("missing")) {
            when(repository.observeCompaction("session")).thenReturn(Optional.empty());
        } else {
            session.setState("running");
            observe(List.of());
        }
        assertError(kind.equals("missing") ? RuntimeErrorCode.SESSION_NOT_FOUND : RuntimeErrorCode.SESSION_BUSY);
        verifyNoInteractions(directories, models, factory, ids, coordinator);
    }

    @Test
    void shouldRestoreObservedMessagesBindInternalUsageAndReturnDetachedCompletion() {
        MateCredentials credentials = MateCredentials.appKey("test-id", "test-key", "test-token");
        var result = service.compact("session", credentials, Locale.CHINA).toCompletableFuture();
        var holder = registry.find("session").orElseThrow();
        var execution = (RuntimeCompactionExecution) holder.activeExecution().orElseThrow();
        assertThat(execution.runId()).isEqualTo("internal-usage");
        assertThat(execution.acceptingControls()).isFalse();
        assertThat(holder.thinking()).isTrue();
        verify(agent)
                .replaceMessages(
                        List.of(new UserMessage("hello", now.toInstant().toEpochMilli())));
        var request = ArgumentCaptor.forClass(ManagedAgentSessionRequest.class);
        verify(factory).create(request.capture());
        assertThat(request.getValue().mateCredentials()).isSameAs(credentials);
        verify(repository).acceptCompaction(session, now);
        verify(coordinator).start(holder, execution, Locale.CHINA);
        assertThat(result.cancel(true)).isTrue();
        assertThat(execution.completion()).isNotDone();
        registry.complete(holder, execution);
        execution.finishCompaction(19L, null);
        assertThat(execution.result().toCompletableFuture().join())
                .isEqualTo(new RuntimeCompactionResultDTO(true, 19L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "model", "factory", "restore", "restore-close"})
    void shouldKeepDatabaseIdleAndReturnCapacityOnPreparationFailure(String phase) {
        var failure = new IllegalStateException("preparation");
        switch (phase) {
            case "directory" -> when(directories.resolve("agent")).thenThrow(failure);
            case "model" ->
                when(models.resolveAvailableModel(directory, "model")).thenThrow(failure);
            case "factory" -> when(factory.create(any())).thenThrow(failure).thenReturn(managed);
            case "restore", "restore-close" ->
                doThrow(failure).doNothing().when(agent).replaceMessages(any());
            default -> throw new AssertionError(phase);
        }
        if (phase.equals("restore-close")) {
            doThrow(new IllegalStateException("initialization close"))
                    .when(managed)
                    .close();
        }
        assertThatThrownBy(() -> service.compact("session", null, Locale.ROOT)).isSameAs(failure);
        verify(repository, never()).acceptCompaction(any(), any());
        verify(repository, never()).finishExecution(any(), any());
        assertThat(registry.find("session")).isEmpty();
        if (phase.startsWith("restore")) {
            verify(managed).close();
        }
        if (phase.equals("restore-close")) {
            assertThat(failure.getSuppressed())
                    .extracting(Throwable::getMessage)
                    .containsExactly("initialization close");
        }
        verifyNoInteractions(coordinator);
        assertThat(registerOther().sessionId()).isEqualTo("other");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "busy", "database", "start", "cleanup"})
    void shouldReleaseHolderOnAdmissionOrStartupFailureWithoutFinishingACompetingRun(String phase) {
        var failure = new IllegalStateException("failure");
        if (phase.equals("missing") || phase.equals("busy")) {
            when(repository.acceptCompaction(session, now))
                    .thenReturn(
                            phase.equals("missing")
                                    ? CompactionAcceptanceStatus.NOT_FOUND
                                    : CompactionAcceptanceStatus.BUSY);
        } else if (phase.equals("database")) {
            when(repository.acceptCompaction(session, now)).thenThrow(failure);
        } else {
            doThrow(failure).when(coordinator).start(any(), any(), any());
        }
        if (phase.equals("cleanup")) {
            doThrow(new IllegalStateException("close")).when(managed).close();
            doThrow(new IllegalStateException("idle")).when(repository).finishExecution(any(), any());
        }
        assertThatThrownBy(() -> service.compact("session", null, Locale.ROOT)).isInstanceOf(RuntimeException.class);
        verify(managed).close();
        assertThat(registry.find("session")).isEmpty();
        if (phase.equals("start") || phase.equals("cleanup")) {
            verify(repository).finishExecution("session", now);
        } else {
            verify(repository, never()).finishExecution(any(), any());
            verifyNoInteractions(coordinator);
        }
        if (phase.equals("cleanup")) {
            assertThat(failure.getSuppressed())
                    .extracting(Throwable::getMessage)
                    .containsExactly("close", "idle");
        }
        assertThat(registerOther().sessionId()).isEqualTo("other");
    }

    private void observe(List<RuntimeEntryDTO> entries) {
        when(repository.observeCompaction("session"))
                .thenReturn(Optional.of(new RuntimeCompactionSnapshotDTO(session, entries)));
    }

    @Test
    void shouldRejectExhaustedCapacityWithoutAcceptingOrReplacingAnotherHolder() {
        var other = registerOther();
        assertError(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED);
        verify(repository, never()).acceptCompaction(any(), any());
        verify(repository, never()).finishExecution(any(), any());
        verify(managed, never()).close();
        verifyNoInteractions(coordinator);
        assertThat(registry.find("session")).isEmpty();
        assertThat(registry.find("other")).containsSame(other);
        registry.complete(other, other.activeExecution().orElseThrow());
    }

    @Test
    void shouldObserveOnlyAfterTheSharedSessionOperationLockIsReleased() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> registry.withOperationLock("session", () -> {
                entered.countDown();
                release.orTimeout(5, TimeUnit.SECONDS).join();
                session.setState("running");
                return null;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var contender = executor.submit(() -> assertError(RuntimeErrorCode.SESSION_BUSY));
                assertThatThrownBy(() -> contender.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                verify(repository, never()).observeCompaction("session");
                release.complete(null);
                owner.get(5, TimeUnit.SECONDS);
                contender.get(5, TimeUnit.SECONDS);
                verify(repository).observeCompaction("session");
                verifyNoInteractions(directories, models, factory, coordinator, ids);
            } finally {
                release.complete(null);
            }
        }
    }

    private RuntimeSessionHolder registerOther() {
        return registry.register(
                "other", directory, mock(Model.class), false, List.of(), new RuntimeCompactionExecution(), null);
    }

    private void assertError(RuntimeErrorCode expected) {
        assertThatThrownBy(() -> service.compact("session", null, Locale.ROOT))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(expected));
    }
}
