/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.huawei.hicampus.claw.codingagent.test.Log4j2TestAppender;
import com.huawei.hicampus.claw.codingagent.tool.plan.PlanReviewException;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.UserQuestionException;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.Test;

class RuntimeActiveExecutionPlanReviewTest {

    @Test
    void shouldLogQuestionConflictBeforeRejectingPlanReview() {
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        execution.applyMode(ClawConstants.Agent.MODE_PLAN);
        var question = new QuestionRequestSnapshotDTO(
                List.of(new QuestionDTO("question-1", "Question", null, List.of(), false)));
        execution.beginQuestion("ask-1", "question-event-1", question);
        Logger logger = (Logger) LogManager.getLogger(RuntimeActiveExecution.class);
        Log4j2TestAppender logs = new Log4j2TestAppender("plan-review-conflict-logs");
        logs.start();
        logger.addAppender(logs);
        try {
            PlanReviewException failure = assertThrows(
                    PlanReviewException.class, () -> execution.beginPlanReview("exit-1", "plan-event-1", "# Plan"));

            assertThat(failure.stableErrorCode()).isEqualTo("PLAN_REVIEW_UNAVAILABLE");
            assertThat(failure).hasNoCause();
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMessage().getFormattedMessage())
                        .contains("operation=runtime.plan.review.begin")
                        .contains("errorCode=PLAN_REVIEW_UNAVAILABLE");
                String loggedToolCallId = event.getContextData().getValue("toolCallId");
                String loggedPlanEventId = event.getContextData().getValue("planEventId");
                assertThat(loggedToolCallId).isEqualTo("exit-1");
                assertThat(loggedPlanEventId).isEqualTo("plan-event-1");
                assertThat(event.getThrown()).isInstanceOf(UserQuestionException.class);
            });
        } finally {
            logger.removeAppender(logs);
            logs.stop();
        }
    }
}
