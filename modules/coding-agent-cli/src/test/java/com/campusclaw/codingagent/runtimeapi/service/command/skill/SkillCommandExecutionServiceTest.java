/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.service.command.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtime.AgentRuntimeException;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.dto.command.SkillCommandInputDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeV2MessageEventService;
import com.campusclaw.codingagent.runtimeapi.vo.SkillCommandRequestVO;
import com.campusclaw.common.constant.ClawConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SkillCommandExecutionServiceTest {
    private static final String FILE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final String FILE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final RuntimeV2MessageEventService events = mock(RuntimeV2MessageEventService.class);

    private final SkillCommandExecutionService service = new SkillCommandExecutionService(events);

    private final MateCredentials credentials = MateCredentials.jwt("caller", "secret", "token");

    private final AtomicReference<String> prepared = new AtomicReference<>();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"pdf", "skill:", "skill:PDF", "skill:pdf--x"})
    void testRejectsInvalidRequestNamespaceWithoutRuntimeWork(String name) {
        var request = SkillCommandRequestVO.builder().name(name).build();
        RuntimeApiException error = assertThrows(
                RuntimeApiException.class, () -> service.executeRequest("session", request, Locale.CHINA, credentials));

        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        assertThat(error.getCause()).isNull();
        verifyNoInteractions(events);
    }

    @Test
    void testRejectsAbsentRequestWithoutRuntimeWork() {
        RuntimeApiException error = assertThrows(
                RuntimeApiException.class, () -> service.executeRequest("session", null, Locale.CHINA, credentials));

        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void testRejectsInvalidInputBeforeRuntimeWork(SkillCommandInputDTO input) {
        RuntimeApiException error = assertThrows(
                RuntimeApiException.class, () -> service.execute("session", input, Locale.CHINA, credentials));

        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        assertThat(error.getCause()).isNull();
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "  分析订单\n"})
    void testNormalizesOnlyAbsentInstructionsAndKeepsFilesInOrder(String arguments) {
        RuntimeEventStream stream = configureExpansion("完整 Skill 正文\n");
        var input = input("pdf", arguments, List.of(FILE_B, FILE_A));

        assertThat(service.execute("session", input, Locale.CHINA, credentials)).isSameAs(stream);
        assertThat(prepared.get())
                .isEqualTo(
                        arguments == null || arguments.isBlank() ? "完整 Skill 正文\n" : "完整 Skill 正文\n\n\n" + arguments);
        assertThat(input.getArguments()).isEqualTo(arguments);
        verify(events)
                .submitPreparedMessage(
                        eq("session"),
                        eq("/skill:pdf" + (arguments == null || arguments.isBlank() ? "" : " " + arguments)),
                        any(),
                        eq(List.of(FILE_B, FILE_A)),
                        eq(Locale.CHINA),
                        eq(credentials));
    }

    @Test
    void testCopiesMutableInputBeforeDeferredPreparation() {
        var input = input("pdf", "original", new ArrayList<>(List.of(FILE_A)));
        when(events.submitPreparedMessage(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    input.setSkillName("different");
                    input.setArguments("mutated");
                    input.getFileIds().clear();
                    assertThat(call.<String>getArgument(1)).isEqualTo("/skill:pdf original");
                    BiFunction<String, PreparedAgentRuntime, String> prepare = call.getArgument(2);
                    prepared.set(prepare.apply("agent", runtime("body")));
                    List<String> files = call.getArgument(3);
                    assertThat(files).containsExactly(FILE_A);
                    assertThrows(UnsupportedOperationException.class, () -> files.add("other"));
                    return mock(RuntimeEventStream.class);
                });

        service.execute("session", input, Locale.CHINA, credentials);

        assertThat(prepared.get()).isEqualTo("body\n\noriginal");
    }

    @Test
    void testAcceptsExactExpandedLimitAndRejectsOneMoreCodeUnit() {
        String content = "x".repeat(ClawConstants.RuntimeApi.MAX_MESSAGE_CHARACTERS - 3);
        configureExpansion(content);
        service.execute("session", input("pdf", "y", null), Locale.CHINA, credentials);
        assertThat(prepared.get()).isEqualTo(content + "\n\ny");

        RuntimeApiException error = assertThrows(
                RuntimeApiException.class,
                () -> service.execute("session", input("pdf", "yz", null), Locale.CHINA, credentials));
        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n"})
    void testRejectsMissingInstructionsWithoutFallingBackToArguments(String content) {
        configureExpansion(content);
        RuntimeApiException error = assertThrows(
                RuntimeApiException.class,
                () -> service.execute("session", input("pdf", "request", null), Locale.CHINA, credentials));

        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        assertThat(prepared.get()).isNull();
    }

    @Test
    void testTranslatesFailuresWithoutExposingSecretsOrBody() {
        when(events.submitPreparedMessage(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AgentRuntimeException("secret body"))
                .thenThrow(new IllegalStateException("secret body"))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.AGENT_MODEL_NOT_CONFIGURED));
        for (RuntimeErrorCode expected : List.of(
                RuntimeErrorCode.AGENT_NOT_AVAILABLE,
                RuntimeErrorCode.COMMAND_EXECUTION_FAILED,
                RuntimeErrorCode.MODEL_NOT_AVAILABLE)) {
            RuntimeApiException error = assertThrows(
                    RuntimeApiException.class,
                    () -> service.execute("session", input("pdf", null, null), Locale.CHINA, credentials));
            assertThat(error.errorCode()).isEqualTo(expected);
            assertThat(error.getMessage()).isEqualTo(expected.name());
            assertThat(error.getCause()).isNull();
        }
    }

    private RuntimeEventStream configureExpansion(String content) {
        var stream = mock(RuntimeEventStream.class);
        when(events.submitPreparedMessage(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    BiFunction<String, PreparedAgentRuntime, String> prepare = call.getArgument(2);
                    prepared.set(prepare.apply("agent", runtime(content)));
                    return stream;
                });
        return stream;
    }

    private static Stream<SkillCommandInputDTO> invalidInputs() {
        return Stream.concat(
                Stream.of(null, "", "-pdf", "pdf-", "pdf--x", "PDF", "pdf_", "skill:pdf", "a".repeat(65))
                        .map(name -> input(name, null, null)),
                Stream.of(
                        null,
                        input("pdf", "x".repeat(262145), null),
                        input("pdf", null, List.of(FILE_A, FILE_A)),
                        input("pdf", null, List.of(" ")),
                        input("pdf", null, Arrays.asList("a", null)),
                        input(
                                "pdf",
                                null,
                                IntStream.range(0, 5)
                                        .mapToObj(index -> "%032x".formatted(index))
                                        .toList())));
    }

    private static SkillCommandInputDTO input(String name, String arguments, List<String> files) {
        var input = new SkillCommandInputDTO();
        input.setSkillName(name);
        input.setArguments(arguments);
        input.setFileIds(files);
        return input;
    }

    private static PreparedAgentRuntime runtime(String content) {
        var metadata = new AgentRuntime(
                List.of("model"), null, null, null, null, "Agent", true, "agent", "agent", "system", null, "v2");
        var skill = new SkillInfo("pdf", "skill-1", "v2", "description", null, content, null, null, null, null);
        return new PreparedAgentRuntime("agent", Path.of("/unread-private-path"), metadata, List.of(skill));
    }
}
