/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedControlEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanConfirmationAcceptanceDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanConfirmationEventsDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanModeEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanProposalSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.CommittedEventProjection;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeCommittedEventFactory;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryCodec;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEntryIdGenerator;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeExecutionControlMapper;
import com.campusclaw.codingagent.runtimeapi.mapper.RuntimeSessionMapper;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepositoryOpenGaussIT.OpenGaussTestConfiguration;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionControlDispatcher;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.campusclaw.codingagent.runtimeapi.session.PlanConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionState;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeExecutionTerminalReason;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用两个持久化实例和真实 openGauss 验证计划审核事务、竞争及历史同源投影。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimePlanReviewOpenGaussIT {
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 15, 1, 0, 0, 0, ZoneOffset.UTC);

    private static AnnotationConfigApplicationContext context;

    private RuntimeSessionRepository sessions;

    private RuntimeExecutionControlRepository controls;

    private RuntimeExecutionPersistenceService persistence;

    private QueueIds ids;

    private JdbcTemplate jdbc;

    private RuntimeEntryCodec codec;

    private RuntimeCommittedEventFactory events;

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
        StaticMessageSource messages = new StaticMessageSource();
        codec = new RuntimeEntryCodec(mapper, messages);
        events = new RuntimeCommittedEventFactory(mapper, messages);
        truncateRuntimeTables();
    }

    @Test
    void databaseDefaultsModeAndRejectsInvalidMode() {
        insertBareSession("default-mode", null);

        assertThat(jdbc.queryForObject("SELECT mode FROM t_sessions WHERE id = ?", String.class, "default-mode"))
                .isEqualTo("build");
        assertThatThrownBy(() -> insertBareSession("invalid-mode", "invalid"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvalCommitsReceiptBeforeModeAndHistoryKeepsTheSameShape() {
        PlanFixture fixture = seedPlanReview("ordered");
        var acceptance = acceptPlan(fixture, "approved", PlanConfirmationResult.APPROVE, null);

        assertThat(acceptance.target().segmentId()).isEqualTo("approved-segment");
        RuntimeSessionDTO stored = sessions.find(fixture.session().getId()).orElseThrow();
        assertThat(stored.getMode()).isEqualTo("build");
        List<CommittedEventDTO> history =
                sessions.findEventPage(stored.getId(), 0, 20).orElseThrow();
        assertThat(history)
                .extracting(CommittedEventDTO::getType)
                .containsSubsequence(
                        "agent.plan", "session.status_idle", "user.plan_confirmation", "session.mode_changed");
        assertProjectedApproval(history, fixture.planEventId());
    }

    @Test
    void twoRepositoriesAcceptAndClaimOneDecisionOnly() throws Exception {
        PlanFixture fixture = seedPlanReview("repositories");
        RuntimeExecutionControlRepository first = newControlRepository();
        RuntimeExecutionControlRepository second = newControlRepository();

        List<PlanConfirmationAcceptanceDTO> accepted = racePlanApprovals(fixture, first, second);

        assertThat(accepted)
                .extracting(PlanConfirmationAcceptanceDTO::status)
                .containsExactlyInAnyOrder(
                        PlanConfirmationAcceptanceDTO.Status.ACCEPTED,
                        PlanConfirmationAcceptanceDTO.Status.NOT_PENDING);
        assertThat(count("t_session_plan_confirmations", fixture.session().getId()))
                .isOne();
        List<Boolean> claims = racePlanClaims(fixture, first, second);
        assertThat(claims).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void interruptClearsPlanPointersAndRejectsLateConfirmations() {
        PlanFixture fixture = seedPlanReview("interrupt");
        RuntimeEntryDTO interrupt = codec.userInterruptEntry(
                fixture.session().getId(), "interrupt-entry", fixture.target().rootEventId(), NOW.plusSeconds(3));
        CommittedEventDTO interruptEvent =
                events.userInterrupt(interrupt, fixture.target().rootEventId());

        persistence.acceptInterrupt(
                fixture.session().getId(),
                fixture.target().rootEventId(),
                interrupt,
                interruptEvent,
                NOW.plusSeconds(3));

        var state = controls.find(fixture.target()).orElseThrow();
        assertThat(state.getState()).isEqualTo(RuntimeExecutionState.STOPPING);
        assertThat(state.getPendingPlanEventId()).isNull();
        assertThat(state.getPendingToolCallId()).isNull();
        assertLatePlanConfirmationRejected(fixture, RuntimeErrorCode.EXECUTION_STOPPING);
    }

    @Test
    void planAndOrdinaryConfirmationsCannotConsumeEachOther() {
        PlanFixture plan = seedPlanReview("mixed-plan");
        assertOrdinaryConfirmationRejected(plan);
        PlanFixture ordinary = seedOrdinaryConfirmation("mixed-tool");
        assertLatePlanConfirmationRejected(ordinary, RuntimeErrorCode.PLAN_CONFIRMATION_NOT_PENDING);
    }

    @Test
    void revisionMakesOldPlanStaleAndKeepsNewPlanReviewable() {
        PlanFixture original = seedPlanReview("revision");
        var revised = acceptPlan(original, "revised", PlanConfirmationResult.REVISE, "补充回滚步骤");
        PlanFixture replacement = seedReplacementPlan(original, revised.target(), "replacement");

        assertLatePlanConfirmationRejected(original, RuntimeErrorCode.PLAN_CONFIRMATION_NOT_PENDING);
        var accepted = acceptPlan(replacement, "replacement", PlanConfirmationResult.APPROVE, null);

        assertThat(accepted.target()).isNotNull();
        assertThat(count("t_session_plan_confirmations", original.session().getId()))
                .isEqualTo(2);
        assertThat(sessions.find(original.session().getId()).orElseThrow().getMode())
                .isEqualTo("build");
    }

    @Test
    void concurrentApprovalAndInterruptLeaveAConsistentCommittedOrder() throws Exception {
        PlanFixture fixture = seedPlanReview("approve-interrupt");

        RaceOutcome outcome = raceApprovalAndInterrupt(fixture);

        assertThat(outcome.interruptError()).isNull();
        assertThat(outcome.planStatus())
                .isIn(
                        PlanConfirmationAcceptanceDTO.Status.ACCEPTED,
                        PlanConfirmationAcceptanceDTO.Status.EXECUTION_STOPPING);
        assertRaceState(fixture, outcome.planStatus());
    }

    @Test
    void approvedContinuationInterruptedBeforeClaimTerminatesTheAuthoritativeSegment() {
        PlanFixture fixture = seedPlanReview("approved-stop");
        LocalPlan local = pendingLocalPlanExecution(fixture);
        var decision = acceptPlan(fixture, "approved-stop-confirmed", PlanConfirmationResult.APPROVE, null);
        ExecutionTargetDTO stopTarget = interruptAfterApproval(fixture);
        assertThat(controls.findPendingControls(List.of(fixture.target())))
                .singleElement()
                .satisfies(signal -> assertThat(signal.target()).isEqualTo(stopTarget));
        RuntimeSessionHolder holder = dispatchStop(local.execution(), stopTarget);

        assertThat(decision.target()).isEqualTo(stopTarget);
        assertThat(local.execution().target()).isEqualTo(stopTarget);
        assertThat(local.execution().abortRequested()).isTrue();
        assertThatThrownBy(local.decision()::join).isInstanceOf(CancellationException.class);
        verify(holder).abort();
        commitTerminated(stopTarget);

        assertThat(controls.find(stopTarget).orElseThrow().getState()).isEqualTo(RuntimeExecutionState.TERMINAL);
        assertThat(sessions.find(fixture.session().getId()).orElseThrow().getState())
                .isEqualTo("idle");
    }

    private void truncateRuntimeTables() {
        RuntimeOpenGaussTestCleaner.truncate(jdbc);
    }

    private void insertBareSession(String id, String mode) {
        String columns = "id, agent_id, model_id, state, thinking, resource_version, created_at, updated_at, cwd";
        String sql = "INSERT INTO t_sessions (" + columns + (mode == null ? "" : ", mode") + ") "
                + "VALUES (?, ?, ?, 'idle', FALSE, 1, ?, ?, ?" + (mode == null ? ")" : ", ?)");
        List<Object> values = new ArrayList<>(List.of(id, "agent", "model", NOW, NOW, "/tmp/plan-mode-it"));
        if (mode != null) {
            values.add(mode);
        }
        jdbc.update(sql, values.toArray());
    }

    private PlanFixture seedPlanReview(String suffix) {
        RuntimeSessionDTO session = newSession("session-plan-" + suffix);
        sessions.create(session);
        ids.reset(suffix + "-execution", suffix + "-segment");
        RuntimeEntryDTO root = codec.userEntry(session.getId(), suffix + "-root", "请制定方案", List.of(), NOW);
        CommittedEventDTO rootEvent = events.userMessage(root, "请制定方案", List.of());
        ExecutionTargetDTO target = persistence
                .acceptMessage(session.getId(), root, rootEvent, true, NOW)
                .target();
        return persistPlan(session, target, suffix);
    }

    private PlanFixture seedReplacementPlan(PlanFixture original, ExecutionTargetDTO target, String suffix) {
        RuntimeSessionDTO session = sessions.find(original.session().getId()).orElseThrow();
        return persistPlan(session, target, suffix);
    }

    private PlanFixture persistPlan(RuntimeSessionDTO session, ExecutionTargetDTO target, String suffix) {
        String toolCallId = suffix + "-exit-call";
        String planEventId = suffix + "-plan-event";
        String planText = "# 实施方案\n\n1. 修改\n2. 验证";
        ToolCall call = new ToolCall(toolCallId, "ExitPlanMode", Map.of());
        String assistantEntryId = persistPlanSource(session, target, suffix, planText, call);
        RuntimeEntryDTO plan =
                codec.planEntry(session.getId(), suffix + "-plan-entry", assistantEntryId, toolCallId, planText, NOW);
        CommittedEventDTO planEvent = events.agentPlan(plan, planEventId, toolCallId, planText, target.rootEventId());
        RuntimeEntryDTO idle = codec.sessionIdleEntry(
                session.getId(), suffix + "-idle", "confirming", target.rootEventId(), null, NOW.plusSeconds(1));
        CommittedEventDTO idleEvent = events.sessionIdle(
                idle, suffix + "-idle-event", "confirming", target.rootEventId(), null, java.util.Locale.CHINA);
        persistence.markPlanConfirming(
                target, assistantEntryId, toolCallId, plan, planEvent, idle, idleEvent, NOW.plusSeconds(1));
        return new PlanFixture(session, target, planEventId, toolCallId);
    }

    private String persistPlanSource(
            RuntimeSessionDTO session, ExecutionTargetDTO target, String suffix, String planText, ToolCall call) {
        String assistantEntryId = suffix + "-assistant-entry";
        AssistantMessage assistant = new AssistantMessage(
                List.of(new TextContent("<proposed_plan>\n" + planText + "\n</proposed_plan>"), call),
                "api",
                "provider",
                "model",
                null,
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                1L);
        RuntimeEntryDTO assistantEntry = codec.assistantEntry(session.getId(), assistantEntryId, assistant, NOW);
        CommittedEventDTO assistantEvent =
                events.agentMessage(assistantEntry, assistantEntryId, "", assistant.usage(), target.rootEventId());
        persistence.appendEntry(target, assistantEntry, List.of(assistantEvent));
        RuntimeEntryDTO toolEntry = codec.toolCallEntry(
                session.getId(), suffix + "-tool-entry", call.id(), call.name(), call.arguments(), false, NOW);
        CommittedEventDTO toolEvent =
                events.agentToolCall(toolEntry, suffix + "-tool-event", call, false, target.rootEventId(), null);
        persistence.appendEntry(target, toolEntry, List.of(toolEvent));
        return assistantEntryId;
    }

    private PlanFixture seedOrdinaryConfirmation(String suffix) {
        RuntimeSessionDTO session = newSession("session-plan-" + suffix);
        sessions.create(session);
        ids.reset(suffix + "-execution", suffix + "-segment");
        RuntimeEntryDTO root = codec.userEntry(session.getId(), suffix + "-root", "执行", List.of(), NOW);
        CommittedEventDTO rootEvent = events.userMessage(root, "执行", List.of());
        ExecutionTargetDTO target = persistence
                .acceptMessage(session.getId(), root, rootEvent, true, NOW)
                .target();
        ToolCall call = new ToolCall(suffix + "-tool-call", "AskUserQuestion", Map.of("question", "继续？"));
        RuntimeEntryDTO tool = codec.toolCallEntry(
                session.getId(), suffix + "-tool-entry", call.id(), call.name(), call.arguments(), true, NOW);
        CommittedEventDTO toolEvent =
                events.agentToolCall(tool, suffix + "-tool-event", call, true, target.rootEventId(), null);
        RuntimeEntryDTO idle = codec.sessionIdleEntry(
                session.getId(), suffix + "-idle", "confirming", target.rootEventId(), null, NOW.plusSeconds(1));
        CommittedEventDTO idleEvent = events.sessionIdle(
                idle, suffix + "-idle-event", "confirming", target.rootEventId(), null, java.util.Locale.CHINA);
        persistence.markToolConfirming(target, call.id(), tool, toolEvent, idle, idleEvent, NOW.plusSeconds(1));
        return new PlanFixture(session, target, suffix + "-not-a-plan", call.id());
    }

    private com.campusclaw.codingagent.runtimeapi.dto.AcceptedControlDTO acceptPlan(
            PlanFixture fixture, String prefix, PlanConfirmationResult result, String feedback) {
        ids.reset(prefix + "-segment");
        RuntimeEntryDTO receipt = planReceipt(fixture, prefix, result, feedback);
        CommittedEventDTO receiptEvent =
                events.userPlanConfirmation(receipt, fixture.planEventId(), result.value(), feedback);
        return persistence.acceptPlanConfirmation(
                fixture.session().getId(),
                fixture.planEventId(),
                result,
                feedback,
                receipt,
                receiptEvent,
                source -> result.isApproved() ? modeEvent(fixture, prefix, receiptEvent.getEventId(), source) : null,
                NOW.plusSeconds(2));
    }

    private RuntimeEntryDTO planReceipt(
            PlanFixture fixture, String prefix, PlanConfirmationResult result, String feedback) {
        return codec.userPlanConfirmationEntry(
                fixture.session().getId(),
                prefix + "-receipt",
                fixture.planEventId(),
                result.value(),
                feedback,
                NOW.plusSeconds(2));
    }

    private PlanModeEventDTO modeEvent(
            PlanFixture fixture, String prefix, String confirmationEventId, String sourceEventId) {
        RuntimeEntryDTO entry = codec.modeChangedEntry(
                fixture.session().getId(),
                prefix + "-mode",
                "plan",
                "build",
                "planApproved",
                sourceEventId,
                confirmationEventId,
                NOW.plusSeconds(2));
        return new PlanModeEventDTO(entry, events.sessionModeChanged(entry));
    }

    private void assertLatePlanConfirmationRejected(PlanFixture fixture, RuntimeErrorCode expected) {
        ids.reset("late-segment");
        RuntimeEntryDTO receipt = planReceipt(fixture, "late", PlanConfirmationResult.APPROVE, null);
        CommittedEventDTO event = events.userPlanConfirmation(
                receipt, fixture.planEventId(), PlanConfirmationResult.APPROVE.value(), null);
        assertThatThrownBy(() -> persistence.acceptPlanConfirmation(
                        fixture.session().getId(),
                        fixture.planEventId(),
                        PlanConfirmationResult.APPROVE,
                        null,
                        receipt,
                        event,
                        source -> modeEvent(fixture, "late", event.getEventId(), source),
                        NOW.plusSeconds(4)))
                .isInstanceOf(RuntimeApiException.class)
                .satisfies(error ->
                        assertThat(((RuntimeApiException) error).errorCode()).isEqualTo(expected));
    }

    private void assertOrdinaryConfirmationRejected(PlanFixture fixture) {
        ids.reset("ordinary-segment");
        RuntimeEntryDTO receipt = codec.userToolConfirmationEntry(
                fixture.session().getId(), "ordinary-receipt", fixture.toolCallId(), "allow", null, NOW.plusSeconds(2));
        CommittedEventDTO event = events.userToolConfirmation(receipt, fixture.toolCallId(), "allow", null);
        assertThatThrownBy(() -> persistence.acceptToolConfirmation(
                        fixture.session().getId(),
                        fixture.toolCallId(),
                        ToolConfirmationResult.ALLOW,
                        null,
                        receipt,
                        event,
                        NOW.plusSeconds(2)))
                .isInstanceOf(RuntimeApiException.class)
                .satisfies(error -> assertThat(((RuntimeApiException) error).errorCode())
                        .isEqualTo(RuntimeErrorCode.TOOL_CONFIRMATION_NOT_PENDING));
    }

    private List<PlanConfirmationAcceptanceDTO> racePlanApprovals(
            PlanFixture fixture, RuntimeExecutionControlRepository first, RuntimeExecutionControlRepository second)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> acceptDirect(fixture, first, "first", ready, start));
            var b = executor.submit(() -> acceptDirect(fixture, second, "second", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private PlanConfirmationAcceptanceDTO acceptDirect(
            PlanFixture fixture,
            RuntimeExecutionControlRepository repository,
            String prefix,
            CountDownLatch ready,
            CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        TransactionTemplate transaction = transaction();
        return transaction.execute(status -> repository.acceptPlanConfirmation(
                fixture.session().getId(),
                fixture.planEventId(),
                prefix + "-receipt",
                prefix + "-segment",
                PlanConfirmationResult.APPROVE,
                null,
                source -> appendDirectApproval(fixture, prefix, source),
                NOW.plusSeconds(2)));
    }

    private PlanConfirmationEventsDTO appendDirectApproval(PlanFixture fixture, String prefix, String sourceEventId) {
        RuntimeEntryDTO receipt = planReceipt(fixture, prefix, PlanConfirmationResult.APPROVE, null);
        CommittedEventDTO receiptEvent = events.userPlanConfirmation(
                receipt, fixture.planEventId(), PlanConfirmationResult.APPROVE.value(), null);
        sessions.appendEntry(receipt, List.of(receiptEvent));
        PlanModeEventDTO mode = modeEvent(fixture, prefix, receiptEvent.getEventId(), sourceEventId);
        sessions.appendEntry(mode.entry(), List.of(mode.event()));
        return new PlanConfirmationEventsDTO(List.of(
                new CommittedControlEventDTO(receiptEvent.getEventId(), receiptEvent.getEventSeq()),
                new CommittedControlEventDTO(
                        mode.event().getEventId(), mode.event().getEventSeq())));
    }

    private List<Boolean> racePlanClaims(
            PlanFixture fixture, RuntimeExecutionControlRepository first, RuntimeExecutionControlRepository second)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> claimDirect(fixture, first, ready, start));
            var b = executor.submit(() -> claimDirect(fixture, second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private boolean claimDirect(
            PlanFixture fixture,
            RuntimeExecutionControlRepository repository,
            CountDownLatch ready,
            CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return Boolean.TRUE.equals(transaction().execute(status -> repository
                .claimPlanConfirmation(fixture.target(), fixture.planEventId(), NOW.plusSeconds(3))
                .isPresent()));
    }

    private RaceOutcome raceApprovalAndInterrupt(PlanFixture fixture) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var plan = executor.submit(() -> raceApprove(fixture, ready, start));
            var interrupt = executor.submit(() -> raceInterrupt(fixture, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new RaceOutcome(plan.get(10, TimeUnit.SECONDS), interrupt.get(10, TimeUnit.SECONDS));
        }
    }

    private PlanConfirmationAcceptanceDTO.Status raceApprove(
            PlanFixture fixture, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            return acceptPlan(fixture, "raced", PlanConfirmationResult.APPROVE, null)
                                    .target()
                            == null
                    ? PlanConfirmationAcceptanceDTO.Status.NOT_PENDING
                    : PlanConfirmationAcceptanceDTO.Status.ACCEPTED;
        } catch (RuntimeApiException error) {
            assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.EXECUTION_STOPPING);
            return PlanConfirmationAcceptanceDTO.Status.EXECUTION_STOPPING;
        }
    }

    private Throwable raceInterrupt(PlanFixture fixture, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            RuntimeEntryDTO entry = codec.userInterruptEntry(
                    fixture.session().getId(),
                    "raced-interrupt",
                    fixture.target().rootEventId(),
                    NOW.plusSeconds(2));
            persistence.acceptInterrupt(
                    fixture.session().getId(),
                    fixture.target().rootEventId(),
                    entry,
                    events.userInterrupt(entry, fixture.target().rootEventId()),
                    NOW.plusSeconds(2));
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    private void assertRaceState(PlanFixture fixture, PlanConfirmationAcceptanceDTO.Status planStatus) {
        RuntimeSessionDTO session = sessions.find(fixture.session().getId()).orElseThrow();
        assertThat(session.getState()).isEqualTo("running");
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM t_session_executions WHERE session_id = ?", String.class, session.getId()))
                .isEqualTo("STOPPING");
        if (planStatus == PlanConfirmationAcceptanceDTO.Status.ACCEPTED) {
            assertThat(session.getMode()).isEqualTo("build");
            assertThat(count("t_session_plan_confirmations", session.getId())).isOne();
        } else {
            assertThat(session.getMode()).isEqualTo("plan");
            assertThat(count("t_session_plan_confirmations", session.getId())).isZero();
        }
    }

    private LocalPlan pendingLocalPlanExecution(PlanFixture fixture) {
        var execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        execution.bindTarget(fixture.target());
        execution.applyMode("plan");
        var decision = execution.beginPlanReview(new PlanProposalSnapshotDTO(
                fixture.target(),
                "assistant-plan",
                fixture.planEventId(),
                fixture.target().rootEventId(),
                fixture.toolCallId(),
                "# 实施方案\n\n1. 修改\n2. 验证"));
        return new LocalPlan(execution, decision);
    }

    private ExecutionTargetDTO interruptAfterApproval(PlanFixture fixture) {
        RuntimeEntryDTO entry = codec.userInterruptEntry(
                fixture.session().getId(),
                "approved-stop-interrupt",
                fixture.target().rootEventId(),
                NOW.plusSeconds(3));
        return persistence
                .acceptInterrupt(
                        fixture.session().getId(),
                        fixture.target().rootEventId(),
                        entry,
                        events.userInterrupt(entry, fixture.target().rootEventId()),
                        NOW.plusSeconds(3))
                .target();
    }

    @SuppressWarnings("unchecked")
    private RuntimeSessionHolder dispatchStop(RuntimeActiveExecution execution, ExecutionTargetDTO stopTarget) {
        RuntimeSessionEngineRegistry engines = mock(RuntimeSessionEngineRegistry.class);
        RuntimeSessionHolder holder = mock(RuntimeSessionHolder.class);
        when(engines.find(stopTarget.sessionId())).thenReturn(java.util.Optional.of(holder));
        when(holder.activeExecution()).thenReturn(java.util.Optional.of(execution));
        doAnswer(invocation -> invocation.<Supplier<Object>>getArgument(1).get())
                .when(engines)
                .withOperationLock(eq(stopTarget.sessionId()), any(Supplier.class));
        var dispatcher = new RuntimeSessionControlDispatcher(engines, controls, java.time.Clock.systemUTC());
        assertThat(dispatcher.dispatchStop(stopTarget)).isTrue();
        return holder;
    }

    private void commitTerminated(ExecutionTargetDTO target) {
        RuntimeEntryDTO idle = codec.sessionIdleEntry(
                target.sessionId(),
                "approved-stop-terminal",
                "terminated",
                target.rootEventId(),
                null,
                NOW.plusSeconds(4));
        CommittedEventDTO event = events.sessionIdle(
                idle, "approved-stop-terminal-event", "terminated", target.rootEventId(), null, java.util.Locale.CHINA);
        persistence.commitTerminal(target, idle, event, RuntimeExecutionTerminalReason.TERMINATED, NOW.plusSeconds(4));
    }

    private void assertProjectedApproval(List<CommittedEventDTO> history, String planEventId) {
        CommittedEventProjection projection = new CommittedEventProjection(new ObjectMapper());
        List<SessionEventResponseVO> projected =
                history.stream().map(projection::project).toList();
        SessionEventResponseVO receipt = projected.stream()
                .filter(event -> "user.plan_confirmation".equals(event.getType()))
                .findFirst()
                .orElseThrow();
        var receiptDetails = (SessionEventResponseVO.UserPlanConfirmationResponseVO) receipt.getDetails();
        assertThat(receiptDetails.getTargetEventId()).isEqualTo(planEventId);
        assertThat(receiptDetails.getResult()).isEqualTo("approve");
        SessionEventResponseVO mode = projected.stream()
                .filter(event -> "session.mode_changed".equals(event.getType()))
                .findFirst()
                .orElseThrow();
        var modeDetails = (SessionEventResponseVO.SessionModeChangedResponseVO) mode.getDetails();
        assertThat(modeDetails.getMode()).isEqualTo("build");
        assertThat(modeDetails.getReason()).isEqualTo("planApproved");
        assertThat(modeDetails.getConfirmationEventId()).isEqualTo(receipt.getEventId());
    }

    private RuntimeExecutionControlRepository newControlRepository() {
        return new MyBatisRuntimeExecutionControlRepository(
                context.getBean(RuntimeExecutionControlMapper.class), context.getBean(RuntimeSessionMapper.class));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    private long count(String table, String sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", Long.class, sessionId);
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
        session.setCwd("/tmp/campusclaw-plan-mode-it");
        return session;
    }

    private static void requireProperty(String name) {
        assertThat(System.getProperty(name))
                .as("必须显式提供真实 openGauss 集成测试参数 %s", name)
                .isNotBlank();
    }

    private record PlanFixture(
            RuntimeSessionDTO session, ExecutionTargetDTO target, String planEventId, String toolCallId) {}

    private record RaceOutcome(PlanConfirmationAcceptanceDTO.Status planStatus, Throwable interruptError) {}

    private record LocalPlan(RuntimeActiveExecution execution, CompletableFuture<?> decision) {}

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
