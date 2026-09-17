/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedControlEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.InterruptRequestDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionControlRepository.TransitionStatus;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionState;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationState;
import com.campusclaw.codingagent.tool.question.QuestionAnswerDTO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用真实 openGauss 验证执行终态与三类未领取决定的原子取消。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeDecisionCancellationOpenGaussIT {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 17, 4, 0, 0, 0, ZoneOffset.UTC);

    private static final OffsetDateTime CLAIMED_AT = NOW.plusSeconds(2);

    private static final OffsetDateTime COMPLETED_AT = NOW.plusSeconds(3);

    private static final OffsetDateTime TERMINAL_AT = NOW.plusSeconds(5);

    private static AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository sessions;

    private RuntimeExecutionControlRepository controls;

    private JdbcTemplate jdbc;

    private long eventSequence;

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
        jdbc = context.getBean(JdbcTemplate.class);
        RuntimeOpenGaussTestCleaner.truncate(jdbc);
        eventSequence = 0L;
    }

    @ParameterizedTest
    @EnumSource(
            value = RuntimeExecutionTerminalReason.class,
            names = {"DONE", "FAILED", "TERMINATED"})
    void cancelsAllPendingDecisionsWhenExecutionTerminates(RuntimeExecutionTerminalReason reason) {
        ExecutionTargetDTO target = seedExecution("session-terminal", "execution-terminal", false);
        List<DecisionFixtureDTO> decisions = seedDecisions(target, ToolConfirmationState.PENDING);
        List<String> receiptsBefore =
                decisions.stream().map(this::receiptPayload).toList();

        assertThat(terminate(target, reason, TERMINAL_AT)).isEqualTo(TransitionStatus.APPLIED);

        assertThat(controls.find(target).orElseThrow()).satisfies(execution -> {
            assertThat(execution.getState()).isEqualTo(RuntimeExecutionState.TERMINAL);
            assertThat(execution.getTerminalReason()).isEqualTo(reason);
            assertThat(execution.getTerminalAt()).isEqualTo(TERMINAL_AT);
        });
        decisions.forEach(decision -> {
            assertDecision(decision, ToolConfirmationState.CANCELED, null, TERMINAL_AT);
            assertCannotClaim(decision);
        });
        assertThat(decisions.stream().map(this::receiptPayload).toList()).isEqualTo(receiptsBefore);
    }

    @Test
    void leavesDecisionsPendingUntilStoppingExecutionActuallyTerminates() {
        ExecutionTargetDTO target = seedExecution("session-stop", "execution-stop", false);
        List<DecisionFixtureDTO> decisions = seedDecisions(target, ToolConfirmationState.PENDING);
        String interruptId = target.executionId() + "-interrupt";
        appendEvent(target.sessionId(), interruptId, "user.interrupt", "{}");

        assertThat(controls.requestInterrupt(target.sessionId(), target.rootEventId(), interruptId, CLAIMED_AT)
                        .status())
                .isEqualTo(InterruptRequestDTO.Status.ACCEPTED);
        assertThat(controls.find(target).orElseThrow().getState()).isEqualTo(RuntimeExecutionState.STOPPING);
        decisions.forEach(decision -> {
            assertDecision(decision, ToolConfirmationState.PENDING, null, null);
            assertCannotClaim(decision);
        });

        assertThat(terminate(target, RuntimeExecutionTerminalReason.TERMINATED, TERMINAL_AT))
                .isEqualTo(TransitionStatus.APPLIED);
        decisions.forEach(decision -> assertDecision(decision, ToolConfirmationState.CANCELED, null, TERMINAL_AT));
    }

    @ParameterizedTest
    @EnumSource(DecisionKind.class)
    void preservesNormalClaimAndAcknowledgement(DecisionKind kind) {
        ExecutionTargetDTO target = seedExecution("session-claim", "execution-claim", false);
        DecisionFixtureDTO decision = seedDecision(kind, target, ToolConfirmationState.PENDING);

        claimAndAcknowledge(decision);

        assertDecision(decision, ToolConfirmationState.COMPLETED, CLAIMED_AT, COMPLETED_AT);
        assertCannotClaim(decision);
        assertThat(terminate(target, RuntimeExecutionTerminalReason.DONE, TERMINAL_AT))
                .isEqualTo(TransitionStatus.APPLIED);
        assertDecision(decision, ToolConfirmationState.COMPLETED, CLAIMED_AT, COMPLETED_AT);
    }

    @ParameterizedTest
    @EnumSource(DecisionKind.class)
    void rejectsCanceledDecisionsWithInvalidTimestamps(DecisionKind kind) {
        ExecutionTargetDTO target = seedExecution("session-constraint", "execution-constraint", false);
        DecisionFixtureDTO decision = seedDecision(kind, target, ToolConfirmationState.PENDING);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> updateCanceledTimestamps(decision, CLAIMED_AT, TERMINAL_AT));
        assertDecision(decision, ToolConfirmationState.PENDING, null, null);

        assertThrows(DataIntegrityViolationException.class, () -> updateCanceledTimestamps(decision, null, null));
        assertDecision(decision, ToolConfirmationState.PENDING, null, null);
    }

    @ParameterizedTest
    @EnumSource(DecisionKind.class)
    void serializesClaimAfterTerminalTransactionCommits(DecisionKind kind) throws Exception {
        ExecutionTargetDTO target = seedExecution("session-concurrent", "execution-concurrent", false);
        DecisionFixtureDTO decision = seedDecision(kind, target, ToolConfirmationState.PENDING);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var terminalUpdated = new CountDownLatch(1);
        var releaseTerminal = new CountDownLatch(1);
        var claimStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var terminal = executor.submit(() -> transaction.execute(status -> {
                TransitionStatus result = terminate(target, RuntimeExecutionTerminalReason.TERMINATED, TERMINAL_AT);
                terminalUpdated.countDown();
                awaitLatch(releaseTerminal);
                return result;
            }));
            awaitLatch(terminalUpdated);
            var claim = executor.submit(() -> {
                claimStarted.countDown();
                return claimDecision(decision);
            });
            awaitLatch(claimStarted);
            assertThatThrownBy(() -> claim.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseTerminal.countDown();

            assertThat(terminal.get(5, TimeUnit.SECONDS)).isEqualTo(TransitionStatus.APPLIED);
            assertThat(claim.get(5, TimeUnit.SECONDS)).isEmpty();
            assertDecision(decision, ToolConfirmationState.CANCELED, null, TERMINAL_AT);
        } finally {
            releaseTerminal.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void onlyCancelsPendingDecisionsOfTheTerminatingExecution() {
        ExecutionTargetDTO legacy = seedExecution("session-scope", "execution-legacy", true);
        List<DecisionFixtureDTO> legacyDecisions = seedDecisions(legacy, ToolConfirmationState.PENDING);
        ExecutionTargetDTO target = seedExecution("session-scope", "execution-current", false);
        List<DecisionFixtureDTO> pending = seedDecisions(target, ToolConfirmationState.PENDING);
        List<DecisionFixtureDTO> claimed = seedHistoricalDecisions(target, "claimed", 3, ToolConfirmationState.CLAIMED);
        List<DecisionFixtureDTO> completed =
                seedHistoricalDecisions(target, "completed", 5, ToolConfirmationState.COMPLETED);
        List<DecisionFixtureDTO> unclaimed =
                seedHistoricalDecisions(target, "unclaimed", 7, ToolConfirmationState.PENDING);
        ExecutionTargetDTO otherSession = seedExecution("session-other", target.executionId(), false);
        List<DecisionFixtureDTO> otherDecisions = seedDecisions(otherSession, ToolConfirmationState.PENDING);

        assertThat(terminate(target, RuntimeExecutionTerminalReason.TERMINATED, TERMINAL_AT))
                .isEqualTo(TransitionStatus.APPLIED);

        pending.forEach(decision -> assertDecision(decision, ToolConfirmationState.CANCELED, null, TERMINAL_AT));
        unclaimed.forEach(decision -> assertDecision(decision, ToolConfirmationState.CANCELED, null, TERMINAL_AT));
        claimed.forEach(decision -> assertDecision(decision, ToolConfirmationState.CLAIMED, CLAIMED_AT, null));
        completed.forEach(
                decision -> assertDecision(decision, ToolConfirmationState.COMPLETED, CLAIMED_AT, COMPLETED_AT));
        legacyDecisions.forEach(decision -> assertDecision(decision, ToolConfirmationState.PENDING, null, null));
        otherDecisions.forEach(decision -> assertDecision(decision, ToolConfirmationState.PENDING, null, null));
    }

    @Test
    void rollsBackTerminalEventSegmentAndAllCancellationsWhenAnUpdateFails() {
        ExecutionTargetDTO target = seedExecution("session-rollback", "execution-rollback", false);
        List<DecisionFixtureDTO> decisions = seedDecisions(target, ToolConfirmationState.PENDING);
        jdbc.execute("ALTER TABLE t_session_question_answers ADD CONSTRAINT ck_it_reject_cancellation "
                + "CHECK (state <> 'CANCELED')");
        try {
            assertThatThrownBy(() -> terminate(target, RuntimeExecutionTerminalReason.FAILED, TERMINAL_AT))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertTerminalRolledBack(target, decisions);
        } finally {
            jdbc.execute("ALTER TABLE t_session_question_answers DROP CONSTRAINT ck_it_reject_cancellation");
        }
        assertThat(terminate(target, RuntimeExecutionTerminalReason.FAILED, TERMINAL_AT))
                .isEqualTo(TransitionStatus.APPLIED);
        decisions.forEach(decision -> assertDecision(decision, ToolConfirmationState.CANCELED, null, TERMINAL_AT));
    }

    @Test
    void repeatedTerminalPreservesOriginalCancellationTimeAndDoesNotAppendAgain() {
        ExecutionTargetDTO target = seedExecution("session-retry", "execution-retry", false);
        List<DecisionFixtureDTO> decisions = seedDecisions(target, ToolConfirmationState.PENDING);
        assertThat(terminate(target, RuntimeExecutionTerminalReason.TERMINATED, TERMINAL_AT))
                .isEqualTo(TransitionStatus.APPLIED);

        assertThat(controls.markTerminal(
                        target,
                        terminalId(target),
                        () -> {
                            throw new AssertionError("重复终态不应再次提交事件");
                        },
                        RuntimeExecutionTerminalReason.TERMINATED,
                        TERMINAL_AT.plusSeconds(20)))
                .isEqualTo(TransitionStatus.ALREADY_APPLIED);

        decisions.forEach(decision -> assertDecision(decision, ToolConfirmationState.CANCELED, null, TERMINAL_AT));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_session_events WHERE session_id = ? AND event_id = ?",
                        Long.class,
                        target.sessionId(),
                        terminalId(target)))
                .isOne();
    }

    @Test
    void rejectsStaleTerminalWithoutCancelingDecisions() {
        ExecutionTargetDTO target = seedExecution("session-stale", "execution-stale", false);
        List<DecisionFixtureDTO> decisions = seedDecisions(target, ToolConfirmationState.PENDING);
        ExecutionTargetDTO stale = new ExecutionTargetDTO(
                target.sessionId(), target.executionId(), target.rootEventId(), previousSegment(target));

        assertThat(controls.markTerminal(
                        stale,
                        terminalId(target),
                        () -> {
                            throw new AssertionError("过期结果段不应提交终态事件");
                        },
                        RuntimeExecutionTerminalReason.TERMINATED,
                        TERMINAL_AT))
                .isEqualTo(TransitionStatus.STALE_TARGET);

        decisions.forEach(decision -> assertDecision(decision, ToolConfirmationState.PENDING, null, null));
        assertThat(controls.find(target).orElseThrow().getState()).isEqualTo(RuntimeExecutionState.RUNNING);
    }

    private void updateCanceledTimestamps(
            DecisionFixtureDTO decision, OffsetDateTime claimedAt, OffsetDateTime completedAt) {
        jdbc.update(
                "UPDATE " + decision.kind().table + " SET state = 'CANCELED', claimed_at = ?, completed_at = ?"
                        + " WHERE session_id = ? AND " + decision.kind().receiptColumn + " = ?",
                claimedAt,
                completedAt,
                decision.target().sessionId(),
                decision.receiptId());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void assertTerminalRolledBack(ExecutionTargetDTO target, List<DecisionFixtureDTO> decisions) {
        assertThat(controls.find(target).orElseThrow().getState()).isEqualTo(RuntimeExecutionState.RUNNING);
        decisions.forEach(decision -> assertDecision(decision, ToolConfirmationState.PENDING, null, null));
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM t_session_execution_segments "
                                + "WHERE session_id = ? AND execution_id = ? AND segment_id = ?",
                        String.class,
                        target.sessionId(),
                        target.executionId(),
                        target.segmentId()))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_session_events WHERE session_id = ? AND event_id = ?",
                        Long.class,
                        target.sessionId(),
                        terminalId(target)))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_session_execution_segment_events "
                                + "WHERE session_id = ? AND event_id = ?",
                        Long.class,
                        target.sessionId(),
                        terminalId(target)))
                .isZero();
    }

    private TransitionStatus terminate(
            ExecutionTargetDTO target, RuntimeExecutionTerminalReason reason, OffsetDateTime terminalAt) {
        return controls.markTerminal(
                target,
                terminalId(target),
                () -> appendEvent(target.sessionId(), terminalId(target), "session.status_idle", "{}"),
                reason,
                terminalAt);
    }

    private ExecutionTargetDTO seedExecution(String sessionId, String executionId, boolean terminal) {
        if (sessions.find(sessionId).isEmpty()) {
            sessions.create(newSession(sessionId));
        }
        var target = new ExecutionTargetDTO(sessionId, executionId, executionId + "-root", executionId + "-active");
        jdbc.update(
                """
                INSERT INTO t_session_executions
                    (session_id, execution_id, root_event_id, state, current_segment_id,
                     terminal_event_id, terminal_reason, terminal_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                executionId,
                target.rootEventId(),
                terminal ? "TERMINAL" : "RUNNING",
                target.segmentId(),
                terminal ? executionId + "-legacy-terminal" : null,
                terminal ? "terminated" : null,
                terminal ? NOW : null,
                NOW,
                NOW);
        insertSegments(target, 1);
        return target;
    }

    private List<DecisionFixtureDTO> seedHistoricalDecisions(
            ExecutionTargetDTO execution, String suffix, int ordinal, ToolConfirmationState state) {
        var historical = new ExecutionTargetDTO(
                execution.sessionId(),
                execution.executionId(),
                execution.rootEventId(),
                execution.executionId() + "-" + suffix);
        insertSegments(historical, ordinal);
        return seedDecisions(historical, state);
    }

    private void insertSegments(ExecutionTargetDTO target, int ordinal) {
        jdbc.update(
                """
                INSERT INTO t_session_execution_segments
                    (session_id, execution_id, segment_id, segment_ordinal, trigger_event_id,
                     trigger_event_seq, state, terminal_event_id, terminal_event_seq, terminal_reason,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'CLOSED', ?, 2, 'confirming', ?, ?)
                """,
                target.sessionId(),
                target.executionId(),
                previousSegment(target),
                ordinal,
                previousSegment(target) + "-trigger",
                previousSegment(target) + "-idle",
                NOW,
                NOW);
        jdbc.update(
                """
                INSERT INTO t_session_execution_segments
                    (session_id, execution_id, segment_id, segment_ordinal, trigger_event_id,
                     trigger_event_seq, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 3, 'OPEN', ?, ?)
                """,
                target.sessionId(),
                target.executionId(),
                target.segmentId(),
                ordinal + 1,
                target.segmentId() + "-trigger",
                NOW,
                NOW);
    }

    private List<DecisionFixtureDTO> seedDecisions(ExecutionTargetDTO target, ToolConfirmationState state) {
        return List.of(DecisionKind.values()).stream()
                .map(kind -> seedDecision(kind, target, state))
                .toList();
    }

    private DecisionFixtureDTO seedDecision(DecisionKind kind, ExecutionTargetDTO target, ToolConfirmationState state) {
        String prefix = target.segmentId() + "-" + kind.name();
        var decision = new DecisionFixtureDTO(kind, target, prefix + "-receipt", prefix + "-request", prefix + "-tool");
        appendEvent(target.sessionId(), decision.targetEventId(), kind.requestType, "{}");
        String payload = kind == DecisionKind.QUESTION
                ? "{\"targetEventId\":\"" + decision.targetEventId()
                        + "\",\"answers\":[{\"id\":\"choice\",\"selected\":[\"A\"]}]}"
                : "{\"result\":\"" + kind.result + "\"}";
        appendEvent(target.sessionId(), decision.receiptId(), kind.receiptType, payload);
        insertDecision(decision, state);
        return decision;
    }

    private void insertDecision(DecisionFixtureDTO decision, ToolConfirmationState state) {
        DecisionKind kind = decision.kind();
        ExecutionTargetDTO target = decision.target();
        String specificColumns = kind == DecisionKind.TOOL ? ", result" : ", target_event_id";
        if (kind == DecisionKind.PLAN) {
            specificColumns += ", result";
        }
        var arguments = new ArrayList<Object>(List.of(
                target.sessionId(),
                target.executionId(),
                decision.receiptId(),
                previousSegment(target),
                target.segmentId(),
                decision.toolCallId(),
                state.name(),
                NOW));
        arguments.add(state == ToolConfirmationState.PENDING ? null : CLAIMED_AT);
        arguments.add(state == ToolConfirmationState.COMPLETED ? COMPLETED_AT : null);
        arguments.add(kind == DecisionKind.TOOL ? kind.result : decision.targetEventId());
        if (kind == DecisionKind.PLAN) {
            arguments.add(kind.result);
        }
        jdbc.update(
                "INSERT INTO " + kind.table + " (session_id, execution_id, " + kind.receiptColumn
                        + ", previous_segment_id, segment_id, tool_call_id, state, created_at, claimed_at, completed_at"
                        + specificColumns + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
                        + (kind == DecisionKind.PLAN ? ", ?" : "") + ")",
                arguments.toArray());
    }

    private CommittedControlEventDTO appendEvent(String sessionId, String eventId, String type, String payload) {
        eventSequence++;
        jdbc.update(
                """
                INSERT INTO t_session_events
                    (session_id, event_id, event_seq, anchor_entry_id, type, created_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                sessionId,
                eventId,
                eventSequence,
                eventId + "-entry",
                type,
                NOW,
                payload);
        return new CommittedControlEventDTO(eventId, eventSequence);
    }

    private void claimAndAcknowledge(DecisionFixtureDTO decision) {
        ExecutionTargetDTO previous = previousTarget(decision.target());
        switch (decision.kind()) {
            case TOOL -> {
                var claimed = controls.claimConfirmation(previous, decision.toolCallId(), CLAIMED_AT)
                        .orElseThrow();
                assertThat(claimed.getConfirmationEventId()).isEqualTo(decision.receiptId());
                assertThat(controls.acknowledgeConfirmation(claimed, COMPLETED_AT))
                        .isTrue();
            }
            case PLAN -> {
                var claimed = controls.claimPlanConfirmation(previous, decision.targetEventId(), CLAIMED_AT)
                        .orElseThrow();
                assertThat(claimed.getConfirmationEventId()).isEqualTo(decision.receiptId());
                assertThat(controls.acknowledgePlanConfirmation(claimed, COMPLETED_AT))
                        .isTrue();
            }
            case QUESTION -> {
                var claimed = controls.claimQuestionAnswer(previous, decision.targetEventId(), CLAIMED_AT)
                        .orElseThrow();
                assertThat(claimed.getAnswerEventId()).isEqualTo(decision.receiptId());
                assertThat(claimed.getAnswers().answers())
                        .containsExactly(new QuestionAnswerDTO("choice", List.of("A"), null));
                assertThat(controls.acknowledgeQuestionAnswer(claimed, COMPLETED_AT))
                        .isTrue();
            }
        }
    }

    private void assertCannotClaim(DecisionFixtureDTO decision) {
        assertThat(claimDecision(decision)).isEmpty();
    }

    private Optional<?> claimDecision(DecisionFixtureDTO decision) {
        ExecutionTargetDTO previous = previousTarget(decision.target());
        return switch (decision.kind()) {
            case TOOL -> controls.claimConfirmation(previous, decision.toolCallId(), TERMINAL_AT);
            case PLAN -> controls.claimPlanConfirmation(previous, decision.targetEventId(), TERMINAL_AT);
            case QUESTION -> controls.claimQuestionAnswer(previous, decision.targetEventId(), TERMINAL_AT);
        };
    }

    private void assertDecision(
            DecisionFixtureDTO decision,
            ToolConfirmationState state,
            OffsetDateTime claimedAt,
            OffsetDateTime completedAt) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, claimed_at, completed_at FROM " + decision.kind().table + " WHERE session_id = ? AND "
                        + decision.kind().receiptColumn + " = ?",
                decision.target().sessionId(),
                decision.receiptId());
        assertThat(row.get("state")).isEqualTo(state.name());
        assertThat(asInstant(row.get("claimed_at"))).isEqualTo(claimedAt == null ? null : claimedAt.toInstant());
        assertThat(asInstant(row.get("completed_at"))).isEqualTo(completedAt == null ? null : completedAt.toInstant());
    }

    private String receiptPayload(DecisionFixtureDTO decision) {
        return jdbc.queryForObject(
                "SELECT payload::text FROM t_session_events WHERE session_id = ? AND event_id = ?",
                String.class,
                decision.target().sessionId(),
                decision.receiptId());
    }

    private static Instant asInstant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private static String terminalId(ExecutionTargetDTO target) {
        return target.executionId() + "-terminal";
    }

    private static String previousSegment(ExecutionTargetDTO target) {
        return target.segmentId() + "-previous";
    }

    private static ExecutionTargetDTO previousTarget(ExecutionTargetDTO target) {
        return new ExecutionTargetDTO(
                target.sessionId(), target.executionId(), target.rootEventId(), previousSegment(target));
    }

    private static RuntimeSessionDTO newSession(String sessionId) {
        var session = new RuntimeSessionDTO();
        session.setId(sessionId);
        session.setAgentId("agent_0123456789ABCDEFGHJKMNP");
        session.setModelId("model-db-it");
        session.setState("running");
        session.setThinking(false);
        session.setMode("build");
        session.setResourceVersion(1L);
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);
        session.setCwd("/tmp/campusclaw-decision-cancellation-it");
        return session;
    }

    private static void requireProperty(String name) {
        assertThat(System.getProperty(name))
                .as("必须显式提供真实 openGauss 集成测试参数 %s", name)
                .isNotBlank();
    }

    private record DecisionFixtureDTO(
            DecisionKind kind, ExecutionTargetDTO target, String receiptId, String targetEventId, String toolCallId) {}

    private enum DecisionKind {
        TOOL(
                "t_session_tool_confirmations",
                "confirmation_event_id",
                "agent.tool_call",
                "user.tool_confirmation",
                "allow"),
        PLAN(
                "t_session_plan_confirmations",
                "confirmation_event_id",
                "agent.plan",
                "user.plan_confirmation",
                "approve"),
        QUESTION("t_session_question_answers", "answer_event_id", "agent.question", "user.question_answer", null);

        private final String table;

        private final String receiptColumn;

        private final String requestType;

        private final String receiptType;

        private final String result;

        DecisionKind(String table, String receiptColumn, String requestType, String receiptType, String result) {
            this.table = table;
            this.receiptColumn = receiptColumn;
            this.requestType = requestType;
            this.receiptType = receiptType;
            this.result = result;
        }
    }
}
