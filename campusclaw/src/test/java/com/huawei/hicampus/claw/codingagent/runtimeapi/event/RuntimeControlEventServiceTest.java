/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.huawei.hicampus.claw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedControlDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.QuestionAnswerEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class RuntimeControlEventServiceTest {
    @Test
    void shouldQueueInterruptReceiptBeforeItsCommittedExecutionTerminal() {
        Fixture fixture = new Fixture();
        fixture.stubInterruptAccepted();

        var accepted = fixture.service.acceptInterrupt("session", "root-event");
        fixture.deliver(
                accepted.acceptance().target(),
                RuntimeResultWaitMode.EXECUTION_TERMINAL,
                List.of(fixture.event(
                        "terminated-event",
                        4L,
                        "session.status_idle",
                        "{\"reason\":\"terminated\",\"sourceEventId\":\"root-event\"}")));

        List<RuntimeSseEventVO> output = collect(accepted.stream());
        assertThat(output)
                .extracting(RuntimeSseEventVO::getEvent)
                .containsExactly("user.interrupt", "session.status_idle");
        assertThat(output.getFirst().getData()).containsEntry("targetEventId", "root-event");
        assertThat(fixture.interruptReceipt.getPayload()).contains("target_event_id");
        verify(fixture.results).notifyCommitted(fixture.interruptTarget);
        assertThat(fixture.waits.registeredResponses()).isZero();
    }

    @Test
    void shouldBindConfirmationReceiptToItsNewContinuationSegment() {
        Fixture fixture = new Fixture();
        fixture.stubConfirmationAccepted();

        var accepted =
                fixture.service.acceptToolConfirmation("session", "tool-call", ToolConfirmationResult.DENY, "请先说明风险");
        fixture.deliver(
                accepted.acceptance().target(),
                RuntimeResultWaitMode.SEGMENT_EVENTS,
                List.of(fixture.event(
                        "done-event",
                        6L,
                        "session.status_idle",
                        "{\"reason\":\"done\",\"sourceEventId\":\"root-event\"}")));

        List<RuntimeSseEventVO> output = collect(accepted.stream());
        assertThat(output)
                .extracting(RuntimeSseEventVO::getEvent)
                .containsExactly("user.tool_confirmation", "session.status_idle");
        assertThat(output.getFirst().getData())
                .containsEntry("toolCallId", "tool-call")
                .containsEntry("result", "deny")
                .containsEntry("denyMessage", "请先说明风险");
        assertThat(fixture.confirmationReceipt.getPayload()).contains("tool_call_id", "deny_message");
        verify(fixture.results).notifyCommitted(fixture.continuationTarget);
    }

    @Test
    void shouldRejectBeforePersistenceWhenWaitCapacityIsExhausted() {
        Fixture fixture = new Fixture();
        fixture.waits
                .reserve(RuntimeResultWaitMode.SEGMENT_EVENTS, (events, terminal) -> true, () -> {})
                .orElseThrow();

        assertThatThrownBy(() -> fixture.service.acceptInterrupt("session", "root-event"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE));

        verify(fixture.persistence, never()).acceptInterrupt(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReleaseReservedResponseWhenAcceptanceIsRejected() {
        Fixture fixture = new Fixture();
        when(fixture.persistence.acceptInterrupt(eq("session"), eq("root-event"), any(), any(), any()))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.SESSION_NOT_RUNNING));

        assertThatThrownBy(() -> fixture.service.acceptInterrupt("session", "root-event"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.SESSION_NOT_RUNNING));

        assertThat(fixture.waits.registeredResponses()).isZero();
        verify(fixture.results, never()).notifyCommitted(any());
    }

    @Test
    void shouldReleaseReservedResponseAndMapMalformedReceiptBeforePersistence() {
        RuntimeCommittedEventFactory events = mock(RuntimeCommittedEventFactory.class);
        Fixture fixture = new Fixture(events);
        when(events.userInterrupt(any(), eq("root-event")))
                .thenAnswer(invocation -> fixture.malformedInterrupt(invocation.getArgument(0)));

        assertThatThrownBy(() -> fixture.service.acceptInterrupt("session", "root-event"))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED));

        assertThat(fixture.waits.registeredResponses()).isZero();
        verify(fixture.persistence, never()).acceptInterrupt(any(), any(), any(), any(), any());
        verify(fixture.results, never()).notifyCommitted(any());
    }

    @Test
    void shouldRejectOversizedQuestionAnswerFrameInsideAcceptanceTransaction() {
        Fixture fixture = Fixture.questionFixture(1L, null);
        fixture.stubQuestionTransaction();

        assertThatThrownBy(() -> fixture.service.acceptQuestionAnswer(
                        "session", "question-event", List.of(new QuestionAnswerDTO("details", List.of(), "answer"))))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_SERVICE_UNAVAILABLE));

        verify(fixture.questions).normalizeCurrent(eq("session"), eq("question-event"), any());
        verify(fixture.results, never()).notifyCommitted(any());
        assertThat(fixture.waits.registeredResponses()).isZero();
    }

    @Test
    void shouldMapQuestionAnswerEncodingFailureBeforeTransactionCanCommit() {
        RuntimeEventEncoder failingEncoder = mock(RuntimeEventEncoder.class);
        when(failingEncoder.committed(any())).thenThrow(new IllegalArgumentException("cannot encode"));
        Fixture fixture = Fixture.questionFixture(64 * 1024L, failingEncoder);
        fixture.stubQuestionTransaction();

        assertThatThrownBy(() -> fixture.service.acceptQuestionAnswer(
                        "session", "question-event", List.of(new QuestionAnswerDTO("details", List.of(), "answer"))))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.EVENT_ACCEPTANCE_FAILED));

        verify(fixture.persistence).acceptQuestionAnswer(eq("session"), eq("question-event"), any(), any(), any());
        verify(fixture.results, never()).notifyCommitted(any());
        assertThat(fixture.waits.registeredResponses()).isZero();
    }

    private static List<RuntimeSseEventVO> collect(RuntimeEventStream stream) {
        List<RuntimeSseEventVO> output = new ArrayList<>();
        stream.attach(Runnable::run, new RuntimeEventSubscriber() {
            @Override
            public void onEvent(RuntimeSseEventVO event) {
                output.add(event);
            }

            @Override
            public void onHeartbeat() {
                throw new AssertionError("completed stream must not emit a heartbeat");
            }

            @Override
            public void onComplete() {}

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });
        return output;
    }

    private static final class Fixture {
        private final RuntimeExecutionPersistenceService persistence = mock(RuntimeExecutionPersistenceService.class);

        private final RuntimeResultPollingService results = mock(RuntimeResultPollingService.class);

        private final RuntimeUserQuestionService questions = mock(RuntimeUserQuestionService.class);

        private final RuntimeResultWaitRegistry waits;

        private final ExecutionTargetDTO interruptTarget =
                new ExecutionTargetDTO("session", "execution", "root-event", "segment-1");

        private final ExecutionTargetDTO continuationTarget =
                new ExecutionTargetDTO("session", "execution", "root-event", "segment-2");

        private final RuntimeControlEventService service;

        private RuntimeEntryDTO interruptReceipt;

        private RuntimeEntryDTO confirmationReceipt;

        private Fixture() {
            this(null, properties(), null, false);
        }

        private Fixture(RuntimeCommittedEventFactory eventFactory) {
            this(eventFactory, properties(), null, false);
        }

        private Fixture(
                RuntimeCommittedEventFactory eventFactory,
                RuntimeEventProperties properties,
                RuntimeEventEncoder suppliedEncoder,
                boolean questionEnabled) {
            ObjectMapper mapper = new ObjectMapper();
            var messages = new RuntimeMessageSourceConfiguration().messageSource();
            RuntimeEntryCodec codec = new RuntimeEntryCodec(mapper, messages);
            waits = new RuntimeResultWaitRegistry(properties);
            var ids = new ArrayDeque<>(List.of("interrupt-event", "confirmation-event", "question-answer-event"));
            RuntimeCommittedEventFactory committedEvents =
                    eventFactory == null ? new RuntimeCommittedEventFactory(mapper, messages) : eventFactory;
            RuntimeEventEncoder encoder = suppliedEncoder == null
                    ? new RuntimeEventEncoder(new CommittedEventProjection(mapper), mapper)
                    : suppliedEncoder;
            service = new RuntimeControlEventService(
                    new RuntimeEventStreamFactory(properties, codec),
                    waits,
                    results,
                    codec,
                    ids::removeFirst,
                    persistence,
                    committedEvents,
                    encoder,
                    Clock.fixed(Instant.parse("2026-09-08T01:02:03.456Z"), ZoneOffset.UTC),
                    questionEnabled ? questions : null);
        }

        private static Fixture questionFixture(long streamBytes, RuntimeEventEncoder encoder) {
            RuntimeEventProperties properties = properties();
            properties.setStreamBufferBytes(streamBytes);
            return new Fixture(null, properties, encoder, true);
        }

        private void stubInterruptAccepted() {
            when(persistence.acceptInterrupt(eq("session"), eq("root-event"), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        interruptReceipt = invocation.getArgument(2);
                        return accepted(invocation, 2, 3, interruptTarget);
                    });
        }

        private void stubConfirmationAccepted() {
            when(persistence.acceptToolConfirmation(
                            eq("session"),
                            eq("tool-call"),
                            eq(ToolConfirmationResult.DENY),
                            any(),
                            any(),
                            any(),
                            any()))
                    .thenAnswer(invocation -> {
                        confirmationReceipt = invocation.getArgument(4);
                        return accepted(invocation, 4, 5, continuationTarget);
                    });
        }

        private void stubQuestionTransaction() {
            when(questions.normalizeCurrent(eq("session"), eq("question-event"), any()))
                    .thenReturn(new QuestionAnswerResultDTO(
                            List.of(new QuestionAnswerDTO("details", List.of(), "answer"))));
            when(persistence.acceptQuestionAnswer(eq("session"), eq("question-event"), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        Supplier<QuestionAnswerEventDTO> answerFactory = invocation.getArgument(3);
                        answerFactory.get();
                        throw new AssertionError("question answer transaction must stop before commit");
                    });
        }

        private AcceptedControlDTO accepted(
                org.mockito.invocation.InvocationOnMock invocation,
                int entryIndex,
                int eventIndex,
                ExecutionTargetDTO target) {
            RuntimeEntryDTO entry = invocation.getArgument(entryIndex);
            CommittedEventDTO event = invocation.getArgument(eventIndex);
            entry.setEntrySeq(1L);
            event.setEventSeq(2L);
            return new AcceptedControlDTO(entry, target);
        }

        private void deliver(ExecutionTargetDTO target, RuntimeResultWaitMode mode, List<CommittedEventDTO> events) {
            var claimed = waits.claimTarget(target);
            assertThat(claimed).singleElement().extracting("mode").isEqualTo(mode);
            waits.deliver(claimed.getFirst(), events, true);
        }

        private CommittedEventDTO event(String id, long seq, String type, String payload) {
            var event = new CommittedEventDTO();
            event.setSessionId("session");
            event.setEventId(id);
            event.setEventSeq(seq);
            event.setAnchorEntryId("entry-" + id);
            event.setType(type);
            event.setCreatedAt(OffsetDateTime.parse("2026-09-08T01:02:04.000Z"));
            event.setPayload(payload);
            return event;
        }

        private CommittedEventDTO malformedInterrupt(RuntimeEntryDTO entry) {
            CommittedEventDTO event = event(entry.getId(), 0L, "user.interrupt", "{}");
            event.setAnchorEntryId(entry.getId());
            return event;
        }

        private static RuntimeEventProperties properties() {
            var properties = new RuntimeEventProperties();
            properties.setStreamBufferEvents(10);
            properties.setStreamBufferBytes(64 * 1024L);
            properties.setHeartbeatInterval(Duration.ofSeconds(1));
            properties.setResultWaitMaxResponses(1);
            properties.setResultWaitTimeout(Duration.ofMinutes(1));
            return properties;
        }
    }
}
