/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.QuestionAnswerEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeUserQuestionService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.huawei.hicampus.claw.codingagent.runtimeapi.session.RuntimeExecutionState;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestParser;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 使用真实 openGauss 验证用户问题答案的锁序、回滚和一次性交接。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeQuestionAnswerOpenGaussIT {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 15, 4, 0, 0, 0, ZoneOffset.UTC);

    private static AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository sessions;

    private RuntimeExecutionControlRepository controls;

    private RuntimeExecutionPersistenceService persistence;

    private RuntimeEntryCodec codec;

    private RuntimeCommittedEventFactory events;

    private RuntimeUserQuestionService questions;

    private QueueIds ids;

    private JdbcTemplate jdbc;

    @BeforeAll
    static void startContext() {
        requireProperty("gaussdb.it.url");
        requireProperty("gaussdb.it.username");
        requireProperty("gaussdb.it.password");
        context = new AnnotationConfigApplicationContext(OpenGaussTestConfiguration.class);
    }

    @AfterAll
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetDatabase() {
        sessions = context.getBean(RuntimeSessionRepository.class);
        controls = context.getBean(RuntimeExecutionControlRepository.class);
        ids = new QueueIds();
        persistence = new RuntimeExecutionPersistenceService(sessions, controls, ids);
        jdbc = context.getBean(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        codec = new RuntimeEntryCodec(mapper, new StaticMessageSource());
        events = new RuntimeCommittedEventFactory(mapper, new StaticMessageSource());
        questions = new RuntimeUserQuestionService(sessions, new QuestionRequestParser(mapper), mapper);
        truncateRuntimeTables();
    }

    @Test
    void commitsOrderedAnswerAndOnlyOneRepositoryCanClaimIt() {
        QuestionFixture fixture = seedQuestion("ordered");

        var accepted = accept(fixture, List.of(new QuestionAnswerDTO("choice", List.of("B"), null)));

        assertThat(accepted.target().rootEventId()).isEqualTo(fixture.target().rootEventId());
        assertThat(sessions.find(fixture.session().getId()).orElseThrow().getMode())
                .isEqualTo("plan");
        assertThat(sessions.findEventPage(fixture.session().getId(), 0, 20).orElseThrow())
                .extracting(CommittedEventDTO::getType)
                .containsSubsequence(
                        "agent.tool_call", "agent.question", "session.status_idle", "user.question_answer");
        var first = controls.claimQuestionAnswer(fixture.target(), fixture.questionEventId(), NOW.plusSeconds(3));
        var second = controls.claimQuestionAnswer(fixture.target(), fixture.questionEventId(), NOW.plusSeconds(3));
        assertThat(first).get().satisfies(decision -> {
            assertThat(decision.getTargetEventId()).isEqualTo(fixture.questionEventId());
            assertThat(decision.getToolCallId()).isEqualTo(fixture.toolCallId());
            assertThat(decision.getAnswers().answers())
                    .containsExactly(new QuestionAnswerDTO("choice", List.of("B"), null));
        });
        assertThat(second).isEmpty();
    }

    @Test
    void stopAndStaleTargetsRejectBeforeAnswerValidationCallback() {
        QuestionFixture stopped = seedQuestion("stopped");
        RuntimeEntryDTO interrupt = codec.userInterruptEntry(
                stopped.session().getId(), "interrupt", stopped.target().rootEventId(), NOW.plusSeconds(2));
        persistence.acceptInterrupt(
                stopped.session().getId(),
                stopped.target().rootEventId(),
                interrupt,
                events.userInterrupt(interrupt, stopped.target().rootEventId()),
                NOW.plusSeconds(2));
        AtomicBoolean validated = new AtomicBoolean();
        var stopping = controls.acceptQuestionAnswer(
                stopped.session().getId(),
                stopped.questionEventId(),
                "bad-answer",
                "bad-segment",
                () -> {
                    validated.set(true);
                    throw new AssertionError("invalid answers must not be inspected after stop");
                },
                NOW.plusSeconds(3));
        assertThat(stopping.status())
                .isEqualTo(
                        com.huawei.hicampus.claw.codingagent.runtimeapi.dto.QuestionAnswerAcceptanceDTO.Status
                                .EXECUTION_STOPPING);
        assertThat(validated).isFalse();

        QuestionFixture answered = seedQuestion("stale");
        accept(answered, List.of(new QuestionAnswerDTO("choice", List.of("A"), null)));
        var stale = controls.acceptQuestionAnswer(
                answered.session().getId(),
                answered.questionEventId(),
                "stale-answer",
                "stale-segment",
                () -> {
                    throw new AssertionError("stale answers must not be inspected");
                },
                NOW.plusSeconds(4));
        assertThat(stale.status())
                .isEqualTo(com.huawei.hicampus.claw.codingagent.runtimeapi.dto.QuestionAnswerAcceptanceDTO.Status.NOT_PENDING);
    }

    @Test
    void callbackFailureRollsBackReceiptDecisionAndContinuationSegment() {
        QuestionFixture fixture = seedQuestion("rollback");
        long entriesBefore = count("t_session_entries", fixture.session().getId());

        assertThatThrownBy(() -> controls.acceptQuestionAnswer(
                        fixture.session().getId(),
                        fixture.questionEventId(),
                        "rollback-answer",
                        "rollback-segment",
                        () -> {
                            QuestionAnswerEventDTO answer = answerEvent(
                                    fixture,
                                    "rollback-answer",
                                    List.of(new QuestionAnswerDTO("choice", List.of("A"), null)));
                            sessions.appendEntry(answer.entry(), List.of(answer.event()));
                            throw new IllegalStateException("force rollback");
                        },
                        NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("t_session_entries", fixture.session().getId())).isEqualTo(entriesBefore);
        assertThat(count("t_session_question_answers", fixture.session().getId()))
                .isZero();
        assertThat(controls.find(fixture.target()).orElseThrow()).satisfies(state -> {
            assertThat(state.getState()).isEqualTo(RuntimeExecutionState.CONFIRMING);
            assertThat(state.getPendingQuestionEventId()).isEqualTo(fixture.questionEventId());
        });
    }

    @Test
    void continuationSegmentFailureRollsBackAlreadyAppendedAnswer() {
        QuestionFixture fixture = seedQuestion("segment-collision");
        long entriesBefore = count("t_session_entries", fixture.session().getId());
        String activeLeafBefore =
                sessions.find(fixture.session().getId()).orElseThrow().getActiveLeafId();
        ids.reset("collision-segment");
        insertConflictingSegment(fixture, "collision-segment");

        assertThatThrownBy(() -> persistence.acceptQuestionAnswer(
                        fixture.session().getId(),
                        fixture.questionEventId(),
                        "collision-answer",
                        () -> answerEvent(
                                fixture,
                                "collision-answer",
                                List.of(new QuestionAnswerDTO("choice", List.of("A"), null))),
                        NOW.plusSeconds(2)))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("t_session_entries", fixture.session().getId())).isEqualTo(entriesBefore);
        assertThat(count("t_session_question_answers", fixture.session().getId()))
                .isZero();
        assertThat(sessions.findCommittedEvent(fixture.session().getId(), "collision-answer"))
                .isEmpty();
        assertThat(sessions.find(fixture.session().getId()).orElseThrow().getActiveLeafId())
                .isEqualTo(activeLeafBefore);
        assertThat(controls.find(fixture.target()).orElseThrow().getPendingQuestionEventId())
                .isEqualTo(fixture.questionEventId());
    }

    private void insertConflictingSegment(QuestionFixture fixture, String segmentId) {
        jdbc.update(
                "INSERT INTO t_session_execution_segments "
                        + "(session_id, execution_id, segment_id, segment_ordinal, trigger_event_id, "
                        + "trigger_event_seq, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                fixture.session().getId(),
                fixture.target().executionId(),
                segmentId,
                99,
                "conflict-trigger",
                99L,
                "OPEN",
                NOW,
                NOW);
    }

    private QuestionFixture seedQuestion(String suffix) {
        RuntimeSessionDTO session = newSession("session-question-" + suffix);
        sessions.create(session);
        ids.reset(suffix + "-execution", suffix + "-segment");
        RuntimeEntryDTO root = codec.userEntry(session.getId(), suffix + "-root", "ask", List.of(), NOW);
        ExecutionTargetDTO target = persistence
                .acceptMessage(session.getId(), root, events.userMessage(root, "ask", List.of()), true, NOW)
                .target();
        String toolCallId = suffix + "-ask-call";
        QuestionRequestSnapshotDTO snapshot = snapshot();
        ToolCall call = new ToolCall(toolCallId, "AskUserQuestion", Map.of("questions", snapshot.questions()));
        RuntimeEntryDTO tool = codec.toolCallEntry(
                session.getId(), suffix + "-tool", call.id(), call.name(), call.arguments(), false, NOW.plusSeconds(1));
        persistence.appendEntry(
                target,
                tool,
                List.of(events.agentToolCall(tool, tool.getId(), call, false, target.rootEventId(), null)));
        String questionEventId = suffix + "-question-event";
        RuntimeEntryDTO question =
                codec.questionEntry(session.getId(), suffix + "-question", toolCallId, snapshot, NOW.plusSeconds(1));
        CommittedEventDTO questionEvent =
                events.agentQuestion(question, questionEventId, toolCallId, snapshot, target.rootEventId());
        RuntimeEntryDTO idle = codec.sessionIdleEntry(
                session.getId(), suffix + "-idle", "confirming", target.rootEventId(), null, NOW.plusSeconds(1));
        CommittedEventDTO idleEvent = events.sessionIdle(
                idle, suffix + "-idle-event", "confirming", target.rootEventId(), null, java.util.Locale.CHINA);
        persistence.markQuestionConfirming(
                target, toolCallId, question, questionEvent, idle, idleEvent, NOW.plusSeconds(1));
        return new QuestionFixture(session, target, questionEventId, toolCallId);
    }

    private com.huawei.hicampus.claw.codingagent.runtimeapi.dto.AcceptedQuestionAnswerDTO accept(
            QuestionFixture fixture, List<QuestionAnswerDTO> submitted) {
        String answerEventId = fixture.questionEventId() + "-answer";
        ids.reset(answerEventId + "-segment");
        return persistence.acceptQuestionAnswer(
                fixture.session().getId(),
                fixture.questionEventId(),
                answerEventId,
                () -> answerEvent(fixture, answerEventId, submitted),
                NOW.plusSeconds(2));
    }

    private QuestionAnswerEventDTO answerEvent(
            QuestionFixture fixture, String answerEventId, List<QuestionAnswerDTO> submitted) {
        var normalized = questions.normalizeCurrent(fixture.session().getId(), fixture.questionEventId(), submitted);
        RuntimeEntryDTO entry = codec.userQuestionAnswerEntry(
                fixture.session().getId(), answerEventId, fixture.questionEventId(), normalized, NOW.plusSeconds(2));
        return new QuestionAnswerEventDTO(
                entry, events.userQuestionAnswer(entry, fixture.questionEventId(), normalized));
    }

    private void truncateRuntimeTables() {
        RuntimeOpenGaussTestCleaner.truncate(jdbc);
    }

    private long count(String table, String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", Long.class, sessionId);
    }

    private static QuestionRequestSnapshotDTO snapshot() {
        return new QuestionRequestSnapshotDTO(List.of(new QuestionDTO(
                "choice",
                "Choose",
                null,
                List.of(
                        new com.huawei.hicampus.claw.codingagent.tool.question.QuestionOptionDTO("A", null),
                        new com.huawei.hicampus.claw.codingagent.tool.question.QuestionOptionDTO("B", null)),
                false)));
    }

    private static RuntimeSessionDTO newSession(String sessionId) {
        RuntimeSessionDTO session = new RuntimeSessionDTO();
        session.setId(sessionId);
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
        session.setModelId("model-db-it");
        session.setState("idle");
        session.setThinking(false);
        session.setMode("plan");
        session.setResourceVersion(1L);
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);
        session.setCwd("/tmp/campusclaw-question-it");
        return session;
    }

    private static void requireProperty(String name) {
        assertThat(System.getProperty(name))
                .as("必须显式提供真实 openGauss 集成测试参数 %s", name)
                .isNotBlank();
    }

    private record QuestionFixture(
            RuntimeSessionDTO session, ExecutionTargetDTO target, String questionEventId, String toolCallId) {}

    private static final class QueueIds implements RuntimeEntryIdGenerator {
        private final ArrayDeque<String> values = new ArrayDeque<>();

        @Override
        public synchronized String nextId() {
            return values.removeFirst();
        }

        private synchronized void reset(String... nextValues) {
            values.clear();
            values.addAll(List.of(nextValues));
        }
    }
}
