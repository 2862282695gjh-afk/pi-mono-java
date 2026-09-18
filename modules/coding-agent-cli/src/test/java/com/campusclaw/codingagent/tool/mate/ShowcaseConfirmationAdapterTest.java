/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingDiagnosisDTO;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingStatus;
import com.campusclaw.codingagent.test.Log4j2TestAppender;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.question.QuestionAnswerDTO;
import com.campusclaw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.campusclaw.codingagent.tool.question.ShowcaseConfirmationAdapter;
import com.campusclaw.codingagent.tool.question.UserQuestionException;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 验证展示确认适配器的原始载荷、本地等待、入口和取消边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
class ShowcaseConfirmationAdapterTest {
    private static final String TOOL_ID = "tool-11111111111111111111111111111111";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMateToolClient client;

    private MateToolSessionState state;

    private RuntimeToolPermissionPolicy permissions;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta(
                TOOL_ID,
                ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                "description",
                Map.of(),
                Map.of(),
                false,
                "allow"));
        client.bindAgent("agent-1", List.of(TOOL_ID));
        state = new MateToolsetFactory(client)
                .createSession("agent-1", Map.of(), MateCredentials.jwt("caller", "token", "access"));
        permissions = mock(RuntimeToolPermissionPolicy.class);
    }

    @Test
    void preparedCallShouldKeepPayloadAndReturnLocalContentWithoutRemoteExecution() throws Exception {
        var expected = new QuestionAnswerResultDTO(List.of(
                new QuestionAnswerDTO(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_ANSWER_ID, List.of(), "真实回答")));
        var adapter = runtimeAdapter((callId, signal) -> expected);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-1", "  ", Map.of("嵌套", List.of(1, nullValue(), Map.of("值", "保留"))));

        var snapshot = adapter.prepare(call);
        var leaked = snapshot.context();
        leaked.withObject("new").put("value", 1);
        var result = tool.execute(call.id(), call.arguments(), new CancellationToken(), null);

        assertThat(snapshot.label()).isEqualTo("  ");
        assertThat(snapshot.context())
                .isEqualTo(MAPPER.valueToTree(call.arguments().get("args")).get("context"));
        assertThat(snapshot.context().has("new")).isFalse();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("{\"content\":\"真实回答\"}");
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void invalidContextShouldFailWithoutStartingAQuestionOrRemoteExecution() {
        var adapter = runtimeAdapter((callId, signal) -> null);
        state.createCallTool(adapter);
        ToolCall call = new ToolCall(
                "call-invalid",
                "CallMateTool",
                Map.of(
                        "tool",
                        ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                        "args",
                        Map.of("label", "x", "context", "bad")));

        Logger logger = (Logger) LogManager.getLogger(ShowcaseConfirmationAdapter.class);
        Log4j2TestAppender logs = new Log4j2TestAppender("showcase-invalid-arguments");
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> adapter.prepare(call))
                    .isInstanceOfSatisfying(
                            ToolArgumentsInvalidException.class, error -> assertThat(error.stableErrorCode())
                                    .isEqualTo(ClawConstants.RuntimeApi.TOOL_ARGUMENTS_INVALID_ERROR_CODE));
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getContextData().<String>getValue("toolCallId"))
                        .isEqualTo("call-invalid");
                assertThat(event.getContextData().<String>getValue("reason")).isEqualTo("context_not_object");
                assertThat(event.getMessage().getFormattedMessage()).doesNotContain("bad", "label");
            });
            assertThat(client.executeCalls()).isZero();
        } finally {
            logger.removeAppender(logs);
            logs.stop();
        }
    }

    @Test
    void cancellationShouldClearPreparedCallForTheNextQuestion() throws Exception {
        var adapter = runtimeAdapter((callId, signal) -> null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall first = call("call-1", "first", Map.of());
        adapter.prepare(first);
        var cancelled = new CancellationToken();
        cancelled.cancel();

        assertThatThrownBy(() -> tool.execute(first.id(), first.arguments(), cancelled, null))
                .isInstanceOf(CancellationException.class);
        ToolCall second = call("call-2", "second", Map.of());
        assertThat(adapter.prepare(second).label()).isEqualTo("second");
    }

    @Test
    void childEntryShouldRejectTargetBeforeRemoteExecution() {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.CHILD_AGENT, null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-child", "question", Map.of());

        assertThatThrownBy(() -> tool.execute(call.id(), call.arguments(), new CancellationToken(), null))
                .isInstanceOfSatisfying(UserQuestionException.class, error -> assertThat(error.stableErrorCode())
                        .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_ENTRY_UNSUPPORTED_ERROR_CODE));
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void cronEntryShouldRejectTargetBeforeRemoteExecution() {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.CRON, null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-cron", "question", Map.of());

        assertThatThrownBy(() -> tool.execute(call.id(), call.arguments(), new CancellationToken(), null))
                .isInstanceOfSatisfying(UserQuestionException.class, error -> assertThat(error.stableErrorCode())
                        .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_ENTRY_UNSUPPORTED_ERROR_CODE));
        assertThat(client.executeCalls()).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = RuntimeToolPermissionPolicy.Decision.class,
            names = {"ASK", "DENY"})
    void nonAllowPermissionShouldRejectWithoutWaitingOrRemoteExecution(RuntimeToolPermissionPolicy.Decision decision) {
        AtomicInteger waits = new AtomicInteger();
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> {
            waits.incrementAndGet();
            return null;
        });
        adapter.bindPermissions(permissions, TOOL_ID);
        when(permissions.decide(org.mockito.ArgumentMatchers.any())).thenReturn(decision);
        state.createCallTool(adapter);

        String expected = decision == RuntimeToolPermissionPolicy.Decision.ASK
                ? ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_REQUIRED_ERROR_CODE
                : ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_DENIED_ERROR_CODE;
        assertQuestionCode(() -> adapter.prepare(call("call-denied", "question", Map.of())), expected);
        assertThat(waits).hasValue(0);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void missingBindingShouldRejectWithoutWaitingOrRemoteExecution() {
        var missingClient = new MockMateToolClient();
        missingClient.bindAgent("agent-1", List.of());
        MateToolSessionState missingState = new MateToolsetFactory(missingClient)
                .createSession("agent-1", Map.of(), MateCredentials.jwt("caller", "token", "access"));
        var missing = runtimeAdapter((callId, signal) -> null, new BindingDiagnosisDTO(BindingStatus.MISSING, null, 0));
        missingState.createCallTool(missing);
        assertQuestionCode(
                () -> missing.prepare(call("call-missing", "question", Map.of())),
                ClawConstants.RuntimeApi.USER_QUESTION_BINDING_MISSING_ERROR_CODE);
        assertThat(missingClient.executeCalls()).isZero();
    }

    @Test
    void ambiguousBindingShouldRejectWithoutWaitingOrRemoteExecution() {
        String otherId = "tool-22222222222222222222222222222222";
        client.addTool(new MateToolMeta(
                otherId,
                ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                "other",
                Map.of(),
                Map.of(),
                false,
                "allow"));
        client.bindSkill("skill-1", List.of(otherId));
        MateToolSessionState ambiguousState = new MateToolsetFactory(client)
                .createSession("agent-1", Map.of("skill", "skill-1"), MateCredentials.jwt("caller", "token", "access"));
        var ambiguous =
                runtimeAdapter((callId, signal) -> null, new BindingDiagnosisDTO(BindingStatus.AMBIGUOUS, null, 2));
        ambiguousState.createCallTool(ambiguous);
        assertQuestionCode(
                () -> ambiguous.prepare(call("call-ambiguous", "question", Map.of())),
                ClawConstants.RuntimeApi.USER_QUESTION_BINDING_AMBIGUOUS_ERROR_CODE);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void discoveredIdentityMismatchShouldRejectBeforeWaitingOrRemoteExecution() {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> null);
        adapter.bindPermissions(permissions, "tool-33333333333333333333333333333333");
        when(permissions.decide(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        state.createCallTool(adapter);

        assertThatThrownBy(() -> adapter.prepare(call("call-mismatch", "question", Map.of())))
                .isInstanceOfSatisfying(UserQuestionException.class, error -> assertThat(error.stableErrorCode())
                        .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_TOOL_ID_MISMATCH_ERROR_CODE));
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void missingPortAndUnboundAdapterShouldUseDedicatedCodes() {
        var missingPort = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, null);
        state.createCallTool(missingPort);
        assertQuestionCode(
                () -> missingPort.prepare(call("call-port", "question", Map.of())),
                ClawConstants.RuntimeApi.USER_QUESTION_PORT_MISSING_ERROR_CODE);

        var unbound = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> null);
        state.createCallTool(unbound);
        assertQuestionCode(
                () -> unbound.prepare(call("call-unbound", "question", Map.of())),
                ClawConstants.RuntimeApi.USER_QUESTION_ADAPTER_UNBOUND_ERROR_CODE);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void missingDiscoveredToolShouldUseDedicatedCode() {
        var missingClient = new MockMateToolClient();
        missingClient.bindAgent("agent-1", List.of());
        MateToolSessionState missingState = new MateToolsetFactory(missingClient)
                .createSession("agent-1", Map.of(), MateCredentials.jwt("caller", "token", "access"));
        var adapter = runtimeAdapter((callId, signal) -> null);
        missingState.createCallTool(adapter);

        assertQuestionCode(
                () -> adapter.prepare(call("call-not-found", "question", Map.of())),
                ClawConstants.RuntimeApi.USER_QUESTION_TOOL_NOT_FOUND_ERROR_CODE);
        assertThat(missingClient.executeCalls()).isZero();
    }

    @Test
    void discoveryFailureShouldPreserveCauseAndLogSafeIdentity() {
        RuntimeException cause = new IllegalStateException("private discovery detail");
        MateToolDiscovery discovery = mock(MateToolDiscovery.class);
        when(discovery.resolveToolId(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME))
                .thenThrow(cause);
        var adapter = runtimeAdapter((callId, signal) -> null);
        adapter.bindContext("session-1", "agent-1");
        adapter.bindDiscovery(discovery);

        CapturedQuestionFailureDTO captured = captureQuestionFailure(
                () -> adapter.prepare(call("call-discovery", "sensitive-label", Map.of("secret", "value"))));

        assertThat(captured.failure().stableErrorCode())
                .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_DISCOVERY_FAILED_ERROR_CODE);
        assertThat(captured.failure().getCause()).isSameAs(cause);
        assertThat(captured.event().getThrown()).isSameAs(captured.failure());
        assertThat(captured.event().getContextData().<String>getValue("entryPoint"))
                .isEqualTo("RUNTIME");
        assertThat(captured.event().getContextData().<String>getValue("toolCallId"))
                .isEqualTo("call-discovery");
        assertThat(captured.event().getContextData().<String>getValue("sessionId"))
                .isEqualTo("session-1");
        assertThat(captured.event().getContextData().<String>getValue("agentId"))
                .isEqualTo("agent-1");
        assertThat(captured.event().getContextData().<String>getValue("trustedToolId"))
                .isEqualTo(TOOL_ID);
        assertThat(captured.event().getMessage().getFormattedMessage())
                .doesNotContain("sensitive-label", "secret", "value");
    }

    @Test
    void preparedStateFailuresShouldDistinguishConflictMissingAndMismatch() {
        var adapter = runtimeAdapter((callId, signal) -> null);
        state.createCallTool(adapter);
        ToolCall first = call("call-first", "first", Map.of());
        adapter.prepare(first);

        assertQuestionCode(
                () -> adapter.prepare(call("call-second", "second", Map.of())),
                ClawConstants.RuntimeApi.USER_QUESTION_PREPARATION_CONFLICT_ERROR_CODE);
        adapter.clearPrepared();
        assertQuestionCode(
                () -> adapter.prepared(first), ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE);
        adapter.prepare(first);
        assertQuestionCode(
                () -> adapter.prepared(call("call-first", "changed", Map.of())),
                ClawConstants.RuntimeApi.USER_QUESTION_CALL_MISMATCH_ERROR_CODE);
        assertThat(adapter.prepared(first)).isPresent();
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void invalidAnswerShouldUseDedicatedCodeAndClearPreparation() throws Exception {
        var invalid = new QuestionAnswerResultDTO(List.of(new QuestionAnswerDTO("wrong", List.of(), "answer")));
        var adapter = runtimeAdapter((callId, signal) -> invalid);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-answer", "question", Map.of());
        adapter.prepare(call);

        assertQuestionCode(
                () -> tool.execute(call.id(), call.arguments(), new CancellationToken(), null),
                ClawConstants.RuntimeApi.USER_QUESTION_ANSWER_INVALID_ERROR_CODE);
        assertQuestionCode(
                () -> adapter.prepared(call), ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE);
        assertThat(adapter.prepare(call("call-next", "next", Map.of())).label()).isEqualTo("next");
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void disabledShowcaseShouldKeepRemoteToolExecution() throws Exception {
        var adapter = new ShowcaseConfirmationAdapter(false, ToolEntryPoint.RUNTIME, null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-remote", "question", Map.of());

        tool.execute(call.id(), call.arguments(), new CancellationToken(), null);

        assertThat(client.executeCalls()).isEqualTo(1);
        assertThat(client.lastCalledTool()).isEqualTo(TOOL_ID);
    }

    private ShowcaseConfirmationAdapter runtimeAdapter(
            com.campusclaw.codingagent.tool.question.UserQuestionPort questions) {
        return runtimeAdapter(questions, new BindingDiagnosisDTO(BindingStatus.BOUND, TOOL_ID, 1));
    }

    private ShowcaseConfirmationAdapter runtimeAdapter(
            com.campusclaw.codingagent.tool.question.UserQuestionPort questions, BindingDiagnosisDTO diagnosis) {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, questions);
        adapter.bindPermissions(permissions, diagnosis);
        when(permissions.decide(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        return adapter;
    }

    private static void assertQuestionCode(CheckedAction action, String expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(UserQuestionException.class, error -> assertThat(error.stableErrorCode())
                        .isEqualTo(expected));
    }

    private static CapturedQuestionFailureDTO captureQuestionFailure(CheckedAction action) {
        Logger logger = (Logger) LogManager.getLogger(UserQuestionException.class);
        Log4j2TestAppender logs = new Log4j2TestAppender("showcase-question-failure-" + System.nanoTime());
        logs.start();
        logger.addAppender(logs);
        try {
            UserQuestionException failure =
                    org.junit.jupiter.api.Assertions.assertThrows(UserQuestionException.class, action::run);
            assertThat(logs.events()).hasSize(1);
            return new CapturedQuestionFailureDTO(failure, logs.events().getFirst());
        } finally {
            logger.removeAppender(logs);
            logs.stop();
        }
    }

    private static ToolCall call(String id, String label, Map<String, Object> context) {
        return new ToolCall(
                id,
                "CallMateTool",
                Map.of(
                        "tool",
                        ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                        "args",
                        Map.of("label", label, "context", context, "extra", true)));
    }

    private static Object nullValue() {
        return MAPPER.nullNode();
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private record CapturedQuestionFailureDTO(UserQuestionException failure, LogEvent event) {}
}
