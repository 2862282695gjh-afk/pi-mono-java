/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.QuestionAnswerDecisionDTO;
import com.campusclaw.codingagent.tool.question.QuestionAnswerDTO;
import com.campusclaw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.campusclaw.codingagent.tool.question.QuestionDTO;
import com.campusclaw.codingagent.tool.question.QuestionRequestSnapshotDTO;

import org.junit.jupiter.api.Test;

/**
 * 运行时唯一等待槽的取消、迟到答案和复用测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/23]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimePendingInteractionTest {
    private static final ExecutionTargetDTO TARGET =
            new ExecutionTargetDTO("session", "execution", "root", "segment-1");

    @Test
    void signalCancellationShouldClearSlotAndRejectLateAnswer() {
        RuntimePendingInteraction pending = new RuntimePendingInteraction();
        pending.beginQuestion("call-1", "question-1", question("first"));
        CancellationToken signal = new CancellationToken();
        signal.cancel();

        assertThrows(CancellationException.class, () -> pending.awaitQuestion("call-1", signal));
        assertThat(pending.claimQuestion(TARGET, "question-1", () -> Optional.of(decision("call-1", "question-1"))))
                .isEmpty();
        assertThat(pending.beginQuestion("call-2", "question-2", question("second")))
                .isNotDone();
    }

    @Test
    void completedClaimShouldRejectDuplicateButRemainAwaitable() throws Exception {
        RuntimePendingInteraction pending = new RuntimePendingInteraction();
        pending.beginQuestion("call-1", "question-1", question("first"));
        QuestionAnswerDecisionDTO decision = decision("call-1", "question-1");
        RuntimePendingInteraction.Claimed<QuestionAnswerDecisionDTO> claimed = pending.claimQuestion(
                        TARGET, "question-1", () -> Optional.of(decision))
                .orElseThrow();
        claimed.complete();
        AtomicInteger duplicateClaims = new AtomicInteger();

        assertThat(pending.claimQuestion(TARGET, "question-1", () -> {
                    duplicateClaims.incrementAndGet();
                    return Optional.of(decision);
                }))
                .isEmpty();
        assertThat(duplicateClaims).hasValue(0);
        assertThat(pending.awaitQuestion("call-1", new CancellationToken())).isEqualTo(decision.getAnswers());
    }

    @Test
    void oldAwaitCleanupShouldNotClearReusedSlot() throws Exception {
        RuntimePendingInteraction pending = new RuntimePendingInteraction();
        var oldWaiting = pending.beginQuestion("call-1", "question-1", question("first"));
        RegistrationSignal signal = new RegistrationSignal();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var awaiting = executor.submit(() -> pending.awaitQuestion("call-1", signal));
            assertThat(signal.registered.await(1L, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.cancel(RuntimePendingInteraction.Kind.USER_QUESTION))
                    .isSameAs(oldWaiting);
            QuestionRequestSnapshotDTO nextQuestion = question("second");
            pending.beginQuestion("call-2", "question-2", nextQuestion);
            oldWaiting.completeExceptionally(new CancellationException("cancelled"));

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> awaiting.get(1L, TimeUnit.SECONDS));
            assertThat(failure.getCause()).isInstanceOf(CancellationException.class);
            assertThat(pending.question("call-2")).isSameAs(nextQuestion);
        }
    }

    private static QuestionRequestSnapshotDTO question(String text) {
        return new QuestionRequestSnapshotDTO(List.of(new QuestionDTO("q", text, null, List.of(), false)));
    }

    private static QuestionAnswerDecisionDTO decision(String toolCallId, String eventId) {
        var decision = new QuestionAnswerDecisionDTO();
        decision.setSessionId(TARGET.sessionId());
        decision.setExecutionId(TARGET.executionId());
        decision.setPreviousSegmentId(TARGET.segmentId());
        decision.setTargetEventId(eventId);
        decision.setToolCallId(toolCallId);
        decision.setAnswers(new QuestionAnswerResultDTO(List.of(new QuestionAnswerDTO("q", List.of(), "answer"))));
        return decision;
    }

    private static final class RegistrationSignal extends CancellationToken {
        private final CountDownLatch registered = new CountDownLatch(1);

        @Override
        public void onCancel(Runnable callback) {
            super.onCancel(callback);
            registered.countDown();
        }
    }
}
