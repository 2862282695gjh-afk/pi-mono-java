/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionCall;
import com.campusclaw.codingagent.runtimeapi.compaction.RuntimeCompactionService;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeCompactionResultDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.CompactCommandContributor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 验证压缩定义、请求作用域、结果隔离及同步和异步错误翻译。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class CompactCommandTest {
    private final RuntimeCompactionService runtime = mock(RuntimeCompactionService.class);

    private final SessionCompactionApplicationService service = new SessionCompactionApplicationService(runtime);

    private final RuntimeCompactionCall call = mock(RuntimeCompactionCall.class);

    private final CompletableFuture<RuntimeCompactionResultDTO> terminal = new CompletableFuture<>();

    private final MateCredentials credentials = MateCredentials.appKey("test-id", "test-key", "test-token");

    private final CompactCommandContributor contributor = new CompactCommandContributor(service);

    CompactCommandTest() {
        when(runtime.start(any(), any(), any())).thenReturn(call);
        when(call.result()).thenReturn(terminal.minimalCompletionStage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldInvokeOnceWithRequestCredentialsAndPreserveResult(String arguments) {
        try (var invocation = service.openInvocation(credentials)) {
            var context = context("idle", invocation);
            var result = contributor
                    .definition()
                    .handler()
                    .execute(context, arguments)
                    .toCompletableFuture();
            verify(runtime).start("session", credentials, Locale.CHINA);
            assertThat(result).isNotDone();
            terminal.complete(new RuntimeCompactionResultDTO(true, 41L));
            assertThat(result.join()).isEqualTo(new RuntimeCompactionResultDTO(true, 41L));
            assertThatThrownBy(() -> invocation.invoke("other", Locale.US)).isInstanceOf(IllegalStateException.class);
            verify(runtime, never()).start("other", credentials, Locale.US);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\n", "custom", "/compact"})
    void shouldRejectNonemptyArgumentsBeforeInvokingRuntime(String arguments) {
        try (var invocation = service.openInvocation(credentials)) {
            assertThatThrownBy(() -> service.execute(context("idle", invocation), arguments))
                    .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                            .isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST));
            verifyNoInteractions(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void shouldDescribeNoInputAndPreviewAvailabilityWithoutRuntimeAccess(String state) {
        var definition = contributor.definition();
        var snapshot = context(state, null).session();
        var descriptor = definition.describe(snapshot);
        assertThat(descriptor.name()).isEqualTo("compact");
        assertThat(descriptor.available()).isEqualTo(state.equals("idle"));
        assertThat(descriptor.input().mode()).isEqualTo("none");
        assertThat(descriptor.input().available()).isFalse();
        assertThat(descriptor.input().acceptsFiles()).isFalse();
        assertThat(definition.admission().unavailableCode(snapshot, false))
                .isEqualTo(state.equals("idle") ? null : "SESSION_BUSY");
        verifyNoInteractions(runtime);
    }

    @Test
    void shouldNotCancelAcceptedWorkOnScopeCloseOrClientCancellation() {
        var invocation = service.openInvocation(credentials);
        var context = context("idle", invocation);
        var result = service.execute(context, "");
        invocation.close();
        assertThat(result.toCompletableFuture().cancel(true)).isTrue();
        assertThat(terminal).isNotDone();
        verify(call, never()).interrupt();
        terminal.complete(new RuntimeCompactionResultDTO(false, null));
        assertThat(result.toCompletableFuture().join()).isEqualTo(new RuntimeCompactionResultDTO(false, null));
    }

    @Test
    void shouldCloseUnusedScopeAndKeepDifferentRequestsIsolated() {
        var unused = service.openInvocation(credentials);
        unused.close();
        assertThat(unused.interrupt()).isFalse();
        assertThatThrownBy(() -> unused.invoke("session", Locale.US)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(runtime);
        MateCredentials other = MateCredentials.jwt("other-id", "other-jwt", "other-token");
        try (var invocation = service.openInvocation(other)) {
            invocation.invoke("other-session", Locale.US);
            verify(runtime).start("other-session", other, Locale.US);
            when(call.interrupt()).thenReturn(true, false);
            assertThat(invocation.interrupt()).isTrue();
            assertThat(invocation.interrupt()).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = RuntimeErrorCode.class,
            names = {
                "SESSION_NOT_FOUND",
                "SESSION_BUSY",
                "AGENT_NOT_AVAILABLE",
                "MODEL_NOT_AVAILABLE",
                "MANAGER_UNAVAILABLE",
                "RUNTIME_CAPACITY_EXCEEDED"
            })
    void shouldPreserveStableSynchronousErrorsWithoutSensitiveCause(RuntimeErrorCode code) {
        when(runtime.start(any(), any(), any()))
                .thenThrow(new RuntimeApiException(code, new IllegalStateException("test-sensitive-value")));
        try (var invocation = service.openInvocation(credentials)) {
            assertThatThrownBy(() -> service.execute(context("idle", invocation), ""))
                    .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                        assertThat(error.errorCode()).isEqualTo(code);
                        assertThat(error.getCause()).isNull();
                        assertThat(error.getMessage()).isEqualTo(code.name());
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"async", "wrapped", "sync", "missing-capability", "empty-result", "model-binding"})
    void shouldMapInternalFailuresToCommandErrorsWithoutCause(String phase) {
        if (phase.equals("sync")) {
            when(runtime.start(any(), any(), any())).thenThrow(new IllegalStateException("test-sensitive-value"));
        }
        try (var invocation = service.openInvocation(credentials)) {
            var context = context("idle", phase.equals("missing-capability") ? null : invocation);
            Throwable failure = phase.equals("model-binding")
                    ? new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED)
                    : new IllegalStateException("test-sensitive-value");
            if (phase.equals("empty-result")) {
                terminal.complete(null);
            } else {
                terminal.completeExceptionally(phase.equals("wrapped") ? new CompletionException(failure) : failure);
            }
            assertThatThrownBy(() ->
                            service.execute(context, "").toCompletableFuture().join())
                    .satisfies(error -> {
                        Throwable mapped = error instanceof CompletionException ? error.getCause() : error;
                        assertThat(mapped).isInstanceOfSatisfying(RuntimeApiException.class, api -> {
                            assertThat(api.errorCode())
                                    .isEqualTo(
                                            phase.equals("model-binding")
                                                    ? RuntimeErrorCode.MODEL_NOT_AVAILABLE
                                                    : RuntimeErrorCode.COMMAND_EXECUTION_FAILED);
                            assertThat(api.getCause()).isNull();
                        });
                    });
        }
    }

    @Test
    void shouldWireTheContributorAndNarrowServiceThroughSpringConstructors() {
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.registerBean(RuntimeCompactionService.class, () -> runtime);
            spring.register(SessionCompactionApplicationService.class, CompactCommandContributor.class);
            spring.refresh();
            assertThat(spring.getBean(CompactCommandContributor.class)
                            .definition()
                            .name())
                    .isEqualTo("compact");
            assertThat(spring.getBean(SessionCompactionApplicationService.class))
                    .isNotSameAs(service);
            verifyNoInteractions(runtime);
        }
    }

    private CommandExecutionContext context(String state, CompactionCommandInvocation invocation) {
        var snapshot = new CommandSessionSnapshotDTO("session", "agent", state, "model", false, 1L);
        return new CommandExecutionContext(
                Locale.CHINA, new ResolvedCommandCatalog(snapshot, List.of(), Map.of()), invocation);
    }

    @Test
    void shouldReleaseTheRequestMonitorBeforeCallingRuntime() {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var invocation = service.openInvocation(credentials)) {
            when(runtime.start(any(), any(), any())).thenAnswer(ignored -> {
                workers.submit(invocation::close).get(2L, TimeUnit.SECONDS);
                return call;
            });
            var result = invocation.invoke("session", Locale.US);
            assertThat(result.toCompletableFuture()).isNotDone();
            when(call.interrupt()).thenAnswer(ignored -> {
                workers.submit(invocation::close).get(2L, TimeUnit.SECONDS);
                return true;
            });
            assertThat(invocation.interrupt()).isTrue();
            verify(runtime).start("session", credentials, Locale.US);
        }
    }
}
