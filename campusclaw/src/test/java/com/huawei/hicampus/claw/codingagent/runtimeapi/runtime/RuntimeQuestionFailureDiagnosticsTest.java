/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.codingagent.test.Log4j2TestAppender;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.codingagent.tool.builtin.UnavailableBuiltInTool;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.UserQuestionException;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.Test;

/**
 * 用户问题等待失败的稳定错误码与安全诊断测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeQuestionFailureDiagnosticsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldDiagnosePendingInteractionConflictWithoutQuestionPayload() {
        RuntimePendingInteraction pending = new RuntimePendingInteraction();
        pending.beginPlan("exit-1", "plan-event-1", "# Existing plan");
        QuestionRequestSnapshotDTO question = QuestionRequestSnapshotDTO.showcaseConfirmation(
                "sensitive-label", MAPPER.valueToTree(Map.of("credential", "sensitive-value")));

        CapturedFailureDTO captured = capture(() -> pending.beginQuestion("ask-1", "question-event-1", question));

        assertThat(captured.failure().stableErrorCode())
                .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_PENDING_CONFLICT_ERROR_CODE);
        assertThat(captured.event().getLevel()).isEqualTo(Level.ERROR);
        assertThat(captured.event().getContextData().<String>getValue("requestedKind"))
                .isEqualTo("USER_QUESTION");
        assertThat(captured.event().getContextData().<String>getValue("requestedToolCallId"))
                .isEqualTo("ask-1");
        assertThat(captured.event().getContextData().<String>getValue("requestedTargetEventId"))
                .isEqualTo("question-event-1");
        assertThat(captured.event().getContextData().<String>getValue("existingKind"))
                .isEqualTo("PLAN_REVIEW");
        assertThat(captured.event().getContextData().<String>getValue("existingToolCallId"))
                .isEqualTo("exit-1");
        assertThat(captured.event().getContextData().<String>getValue("existingTargetEventId"))
                .isEqualTo("plan-event-1");
        assertThat(captured.event().getMessage().getFormattedMessage())
                .contains(
                        "operation=runtime.interaction.begin",
                        "errorCode=USER_QUESTION_PENDING_CONFLICT",
                        "requestedToolCallId=ask-1")
                .doesNotContain("sensitive-label", "sensitive-value", "credential");
    }

    @Test
    void shouldDiagnoseMissingQuestionWaitWithRequestedAndExistingIdentity() {
        RuntimePendingInteraction pending = new RuntimePendingInteraction();
        QuestionRequestSnapshotDTO question = QuestionRequestSnapshotDTO.showcaseConfirmation(
                "sensitive-label", MAPPER.valueToTree(Map.of("credential", "sensitive-value")));
        pending.beginQuestion("ask-existing", "question-event-1", question);

        CapturedFailureDTO captured = capture(() -> pending.awaitQuestion("ask-requested", new CancellationToken()));

        assertThat(captured.failure().stableErrorCode())
                .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_WAIT_MISSING_ERROR_CODE);
        assertThat(captured.event().getContextData().<String>getValue("requestedKind"))
                .isEqualTo("USER_QUESTION");
        assertThat(captured.event().getContextData().<String>getValue("requestedToolCallId"))
                .isEqualTo("ask-requested");
        assertThat(captured.event().getContextData().<String>getValue("existingKind"))
                .isEqualTo("USER_QUESTION");
        assertThat(captured.event().getContextData().<String>getValue("existingToolCallId"))
                .isEqualTo("ask-existing");
        assertThat(captured.event().getContextData().<String>getValue("existingTargetEventId"))
                .isEqualTo("question-event-1");
        assertThat(captured.event().getMessage().getFormattedMessage())
                .doesNotContain("sensitive-label", "sensitive-value", "credential");
    }

    @Test
    void shouldDiagnoseUnavailableBuiltInQuestionEntryWithoutParameters() {
        var tool = new UnavailableBuiltInTool(BuiltInToolName.ASK_USER_QUESTION, MAPPER.createObjectNode());

        CapturedFailureDTO captured = capture(() -> tool.execute(
                "ask-unavailable",
                Map.of("questions", List.of(Map.of("question", "sensitive-question"))),
                new CancellationToken(),
                update -> {}));

        assertThat(captured.failure().stableErrorCode())
                .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_ENTRY_UNSUPPORTED_ERROR_CODE);
        assertThat(captured.event().getContextData().<String>getValue("toolCallId"))
                .isEqualTo("ask-unavailable");
        assertThat(captured.event().getContextData().<String>getValue("toolName"))
                .isEqualTo("AskUserQuestion");
        assertThat(captured.event().getMessage().getFormattedMessage()).doesNotContain("sensitive-question");
    }

    private static CapturedFailureDTO capture(CheckedAction action) {
        Logger logger = (Logger) LogManager.getLogger(UserQuestionException.class);
        Log4j2TestAppender logs = new Log4j2TestAppender("question-failure-" + System.nanoTime());
        logs.start();
        logger.addAppender(logs);
        try {
            UserQuestionException failure = assertThrows(UserQuestionException.class, action::run);
            assertThat(logs.events()).hasSize(1);
            LogEvent event = logs.events().getFirst();
            return new CapturedFailureDTO(failure, event);
        } finally {
            logger.removeAppender(logs);
            logs.stop();
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private record CapturedFailureDTO(UserQuestionException failure, LogEvent event) {}
}
