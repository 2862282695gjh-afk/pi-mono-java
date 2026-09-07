/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.campusclaw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.execution.CommandExecutionContext;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.SessionNameUpdateDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.SessionCommandResultDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.service.command.contributor.NameCommandContributor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证 Name 的真实 Contributor 分派、字符安全、规范化和持久化错误边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/05]
 * @since [br_eCampusCore 26.0.0]
 */
class SessionNamingServiceTest {
    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-05T00:00:00Z");

    private final RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);

    private final SessionNamingService service =
            new SessionNamingService(repository, Clock.fixed(now.toInstant(), ZoneOffset.UTC));

    @ParameterizedTest
    @NullAndEmptySource
    void shouldQueryNullableCurrentNameWithoutWriting(String arguments) {
        RuntimeSessionDTO session = session("idle");
        when(repository.find("session")).thenReturn(Optional.of(session));
        assertThat(service.execute("session", arguments)).isEqualTo(new SessionCommandResultDTO(session, false, null));
        verify(repository).find("session");
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"idle", "running"})
    void shouldExecuteRealContributorInBothStatesWithoutUsingSnapshotForName(String state) {
        var contributor = new NameCommandContributor(service);
        var catalog = new CompositeCommandRegistry(List.of(new BuiltinCommandSource(List.of(contributor))))
                .resolve(session(state), CommandKind.BUILTIN);
        var descriptor = catalog.find("name").orElseThrow();
        assertThat(descriptor.available()).isTrue();
        assertThat(descriptor.input().available()).isTrue();
        assertThat(descriptor.input().mode()).isEqualTo("optional");
        assertThat(descriptor.input().acceptsFiles()).isFalse();
        assertThat(descriptor.input().suggestions()).isEmpty();
        verifyNoInteractions(repository);
        RuntimeSessionDTO current = session(state);
        current.setDisplayName("latest name");
        when(repository.find("session")).thenReturn(Optional.of(current));
        var definition =
                (BuiltinCommandDefinition) catalog.findDefinition("name").orElseThrow();
        var context = new CommandExecutionContext(Locale.CHINA, catalog);
        assertThat(definition
                        .handler()
                        .execute(context, "")
                        .toCompletableFuture()
                        .join())
                .isEqualTo(new SessionCommandResultDTO(current, false, null));
        var renamed = session(state);
        renamed.setDisplayName("new name");
        when(repository.updateName("session", "new name", now))
                .thenReturn(Optional.of(new SessionNameUpdateDTO(renamed, true)));
        assertThat(definition
                        .handler()
                        .execute(context, " new name ")
                        .toCompletableFuture()
                        .join())
                .isEqualTo(new SessionCommandResultDTO(renamed, true, null));
        verify(repository).find("session");
        verify(repository).updateName("session", "new name", now);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("validNames")
    void shouldTrimOnlyEdgesAndAcceptEightyUtf8Bytes(String normalized) {
        var renamed = session("idle");
        renamed.setDisplayName(normalized);
        when(repository.updateName("session", normalized, now))
                .thenReturn(Optional.of(new SessionNameUpdateDTO(renamed, true)));
        assertThat(service.execute("session", " \u2003" + normalized + "\u2003 "))
                .isEqualTo(new SessionCommandResultDTO(renamed, true, null));
        verify(repository).updateName("session", normalized, now);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldReturnUnchangedResultFromLockedRepositoryComparison() {
        var locked = session("running");
        locked.setDisplayName("same");
        when(repository.updateName("session", "same", now))
                .thenReturn(Optional.of(new SessionNameUpdateDTO(locked, false)));
        assertThat(service.execute("session", " same ")).isEqualTo(new SessionCommandResultDTO(locked, false, null));
        verify(repository).updateName("session", "same", now);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void shouldRejectInvalidNamesBeforeAccessingRepository(String arguments) {
        assertError(arguments, RuntimeErrorCode.INVALID_COMMAND_REQUEST, 400);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "updated"})
    void shouldReportMissingSessionForQueryOrUpdate(String arguments) {
        assertError(arguments, RuntimeErrorCode.SESSION_NOT_FOUND, 404);
        if (arguments.isEmpty()) {
            verify(repository).find("session");
        } else {
            verify(repository).updateName("session", arguments, now);
        }
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldTranslatePersistenceFailureWithoutExposingNameOrDatabaseMessage() {
        when(repository.updateName("session", "private name", now))
                .thenThrow(new IllegalStateException("database failure with private name"));
        assertThatThrownBy(() -> service.execute("session", "private name"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.SESSION_NAME_UPDATE_FAILED);
                    assertThat(error.status().value()).isEqualTo(500);
                    assertThat(error.getMessage()).isEqualTo("SESSION_NAME_UPDATE_FAILED");
                    assertThat(error.getCause()).isNull();
                });
    }

    private void assertError(String arguments, RuntimeErrorCode code, int status) {
        assertThatThrownBy(() -> service.execute("session", arguments))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(code);
                    assertThat(error.status().value()).isEqualTo(status);
                });
    }

    private static Stream<String> validNames() {
        return Stream.of("first  second", "a".repeat(80), "中".repeat(26) + "ab", "😀".repeat(20));
    }

    private static Stream<String> invalidNames() {
        Stream<String> controls = IntStream.concat(IntStream.rangeClosed(0, 31), IntStream.rangeClosed(127, 159))
                .mapToObj(value -> "name" + (char) value);
        Stream<String> bidi = IntStream.concat(
                        IntStream.rangeClosed(0x202A, 0x202E), IntStream.rangeClosed(0x2066, 0x2069))
                .mapToObj(value -> "a" + (char) value + "b");
        return Stream.of(
                        controls,
                        bidi,
                        Stream.of(
                                " ",
                                "\u2003",
                                "a".repeat(81),
                                "中".repeat(27),
                                "😀".repeat(20) + "a",
                                "\uD800",
                                "\uDC00",
                                "\nname",
                                "name\r\n"))
                .flatMap(values -> values);
    }

    private static RuntimeSessionDTO session(String state) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId("session");
        session.setAgentId("agent");
        session.setModelId("model");
        session.setState(state);
        session.setResourceVersion(1L);
        return session;
    }
}
