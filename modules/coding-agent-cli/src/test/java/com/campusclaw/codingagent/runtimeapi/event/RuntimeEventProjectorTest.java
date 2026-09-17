/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.campusclaw.agent.event.AgentEndEvent;
import com.campusclaw.agent.event.MessageEndEvent;
import com.campusclaw.agent.event.MessageStartEvent;
import com.campusclaw.agent.event.MessageUpdateEvent;
import com.campusclaw.agent.event.ToolExecutionStartEvent;
import com.campusclaw.agent.event.TurnEndEvent;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.BeforeToolCallResult;
import com.campusclaw.ai.stream.AssistantMessageEvent;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolCallSnapshotResolver;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.session.compaction.CompactionReason;
import com.campusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.campusclaw.codingagent.tool.mate.CallMateTool;
import com.campusclaw.codingagent.tool.mate.MateToolsetFactory;
import com.campusclaw.codingagent.tool.plan.PlanReviewException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 执行事件投影的权威写入与同形输出测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventProjectorTest {
    @Test
    void shouldCommitCompleteEventsAfterBestEffortPreviews() {
        Fixture fixture = fixture(true);
        AssistantMessage assistant = assistant(List.of(
                new ThinkingContent("original reasoning"),
                new TextContent("complete answer"),
                new ToolCall("call-1", "Read", Map.of("path", "/tmp/input"))));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(
                new MessageUpdateEvent(assistant, new AssistantMessageEvent.TextDeltaEvent(1, "complete ", assistant)));
        fixture.projector.onEvent(new MessageUpdateEvent(
                assistant, new AssistantMessageEvent.ThinkingDeltaEvent(0, "original reasoning", assistant)));
        fixture.projector.onEvent(new MessageUpdateEvent(
                assistant, new AssistantMessageEvent.ThinkingEndEvent(0, "original reasoning", assistant)));
        fixture.projector.onEvent(new MessageEndEvent(assistant));
        fixture.projector.onEvent(new ToolExecutionStartEvent("call-1", "Read", Map.of("path", "/tmp/input")));
        fixture.projector.onEvent(new TurnEndEvent(
                assistant,
                List.of(new ToolResultMessage(
                        "call-1", "Read", List.of(new TextContent("file content")), null, false, 1L))));

        assertThat(fixture.output.bestEffort)
                .extracting(event -> event.getData().get("type"))
                .containsExactly("agent.message", "agent.thinking");
        assertThat(fixture.output.committed)
                .extracting(event -> event.getData().get("type"))
                .containsExactly("agent.thinking", "agent.message", "agent.tool_call", "agent.tool_result");
        assertThat(fixture.output.committed)
                .allSatisfy(event -> assertThat(event.isDataOnly()).isTrue());
        assertThat(fixture.persistedEvents)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("agent.thinking", "agent.message", "agent.tool_call", "agent.tool_result");
        assertThat(fixture.projector.terminalReason()).isEqualTo(StopReason.STOP);
        assertThat(fixture.projector.failure()).isNull();
    }

    @Test
    void shouldIgnoreEmptyDeltas() {
        Fixture fixture = fixture(true);
        AssistantMessage assistant = assistant(List.of(new TextContent("answer")));
        fixture.projector.onEvent(new MessageStartEvent(assistant));

        fixture.projector.onEvent(
                new MessageUpdateEvent(assistant, new AssistantMessageEvent.TextDeltaEvent(0, "", assistant)));
        fixture.projector.onEvent(
                new MessageUpdateEvent(assistant, new AssistantMessageEvent.ThinkingDeltaEvent(1, null, assistant)));

        assertThat(fixture.output.bestEffort).isEmpty();
        assertThat(fixture.projector.failure()).isNull();
    }

    @Test
    void shouldUseActualAgentCancellationExitAsTerminatedEvidence() {
        Fixture cancelled = fixture(false);
        Fixture natural = fixture(false);

        cancelled.projector.onEvent(new AgentEndEvent(List.of(), true));
        natural.projector.onEvent(new AgentEndEvent(List.of(), false));

        assertThat(cancelled.projector.terminalReason()).isEqualTo(StopReason.ABORTED);
        assertThat(natural.projector.terminalReason()).isEqualTo(StopReason.STOP);
    }

    @Test
    void shouldPersistFailedToolCallWithoutExecutionStartEvent() {
        Fixture fixture = fixture(false);
        ToolCall call = new ToolCall("call-denied", "Write", Map.of("path", "/tmp/input"));
        AssistantMessage assistant = assistant(List.of(call));
        ToolResultMessage denied = new ToolResultMessage(
                call.id(),
                call.name(),
                List.of(new TextContent("blocked")),
                Map.of("errorCode", "TOOL_CALL_DENIED"),
                true,
                1L);

        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));
        fixture.projector.onEvent(new TurnEndEvent(assistant, List.of(denied)));

        assertThat(fixture.persistedEvents)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("agent.message", "agent.tool_call", "agent.tool_result");
        assertThat(fixture.persistedEvents.get(1).getPayload()).contains("\"requiresConfirmation\":false");
        assertThat(fixture.persistedEvents.get(2).getPayload()).contains("\"isError\":true");
    }

    @Test
    void shouldRejectOversizedCompleteEventBeforePersistence() {
        Fixture fixture = fixture(false, 128L);
        AssistantMessage assistant = assistant(List.of(new TextContent("oversized answer")));

        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        assertThat(fixture.projector.failure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("committed event exceeds the stream capacity");
        assertThat(fixture.projector.terminalReason()).isEqualTo(StopReason.ERROR);
        assertThat(fixture.projector.terminalErrorCode()).isEqualTo("EVENT_PAYLOAD_TOO_LARGE");
        verify(fixture.persistence, never()).appendEntryWithUsage(any(), any(), any(), any(), anyList());
        assertThat(fixture.persistedEvents).isEmpty();
        assertThat(fixture.aborts).hasValue(1);
    }

    @Test
    void shouldRejectOversizedRemoteContinuationBeforePersistence() {
        Fixture fixture = fixture(false, 256L);
        fixture.execution.beginToolConfirmation("call-remote");
        ExecutionTargetDTO resumed = new ExecutionTargetDTO("session-v2", "execution-v2", "event-root", "segment-2");
        assertThat(fixture.execution.resumeToolConfirmation(decision("call-remote", resumed)))
                .isTrue();
        AssistantMessage assistant = assistant(List.of(new TextContent("x".repeat(1024))));

        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        assertThat(fixture.execution.output()).isSameAs(RuntimeEventOutput.persistenceOnly());
        assertThat(fixture.projector.terminalErrorCode()).isEqualTo("EVENT_PAYLOAD_TOO_LARGE");
        verify(fixture.persistence, never()).appendEntryWithUsage(any(), any(), any(), any(), anyList());
    }

    @Test
    void shouldKeepThinkingDisabledForExecutionSnapshot() {
        Fixture fixture = fixture(false);
        AssistantMessage assistant =
                assistant(List.of(new ThinkingContent("original reasoning"), new TextContent("safe answer")));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageUpdateEvent(
                assistant, new AssistantMessageEvent.ThinkingDeltaEvent(0, "original reasoning", assistant)));
        fixture.projector.onEvent(new MessageUpdateEvent(
                assistant, new AssistantMessageEvent.ThinkingEndEvent(0, "original reasoning", assistant)));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        assertThat(fixture.persistedEvents)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("agent.message");
        assertThat(fixture.output.committed).hasSize(1);
        assertThat(fixture.output.committed.getFirst().getData().toString())
                .contains("safe answer")
                .doesNotContain("original reasoning");
        assertThat(fixture.output.bestEffort).isEmpty();
    }

    @Test
    void shouldPreserveOriginalThinkingWithOneIdentityForPreviewAndHistory() {
        Fixture fixture = fixture(true);
        String reasoning = "  先检查日志\n再检查配置 <raw>  ";
        AssistantMessage assistant = assistant(List.of(new ThinkingContent(reasoning)));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageUpdateEvent(
                assistant, new AssistantMessageEvent.ThinkingDeltaEvent(0, reasoning, assistant)));
        fixture.projector.onEvent(
                new MessageUpdateEvent(assistant, new AssistantMessageEvent.ThinkingEndEvent(0, reasoning, assistant)));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        var preview = fixture.output.bestEffort.getFirst().getData();
        var completed = fixture.output.committed.getFirst().getData();
        assertThat(preview).containsEntry("content", reasoning).containsEntry("phase", "delta");
        assertThat(completed)
                .containsEntry("content", reasoning)
                .containsEntry("phase", "completed")
                .containsEntry("eventId", preview.get("eventId"));
        var encoder = new RuntimeEventEncoder(new CommittedEventProjection(new ObjectMapper()), new ObjectMapper());
        assertThat(encoder.committed(fixture.persistedEvents.getFirst()).getData())
                .isEqualTo(completed);
        assertThat(fixture.persistedEvents.stream()
                        .filter(event -> "agent.thinking".equals(event.getType()))
                        .count())
                .isEqualTo(1L);
        assertThat(fixture.projector.failure()).isNull();
    }

    @Test
    void shouldOmitEmptyThinkingAndOpaqueProviderData() {
        Fixture fixture = fixture(true);
        AssistantMessage assistant = assistant(List.of(new ThinkingContent("", "opaque-signature", true)));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(
                new MessageUpdateEvent(assistant, new AssistantMessageEvent.ThinkingEndEvent(0, "", assistant)));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        assertThat(fixture.persistedEvents)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("agent.message");
        assertThat(fixture.output.committed.toString()).doesNotContain("opaque-signature");
        assertThat(fixture.output.bestEffort).isEmpty();
        assertThat(fixture.projector.failure()).isNull();
    }

    @Test
    void shouldCommitAutomaticCompactionWithUsageAndRootIdentity() {
        Fixture fixture = fixture(false);
        RuntimeEntryDTO user = fixture.codec.userEntry(
                "session-v2", "entry-user", "question", List.of(), OffsetDateTime.parse("2026-09-08T01:00:00Z"));
        user.setEntrySeq(1L);
        when(fixture.repository.listCurrentBranchEntries("session-v2", 0L, 500)).thenReturn(List.of(user));
        var result = new SessionCompactionResult(
                "summary", List.of(new UserMessage("question", 1L)), 0, 120, 30, Usage.empty());

        fixture.projector.onCompactionEvent(
                new SessionCompactionCompletedEvent(CompactionReason.THRESHOLD, result, false));

        assertThat(fixture.persistedEvents).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo("session.compacted");
            assertThat(event.getPayload())
                    .contains("\"tokensBefore\":120", "\"estimatedTokensAfter\":30")
                    .contains("\"sourceEventId\":\"event-root\"");
        });
        verify(fixture.persistence)
                .appendEntryWithUsage(eq(fixture.execution.target()), any(), any(), eq(result.usage()), anyList());
        assertThat(fixture.output.committed)
                .extracting(event -> event.getData().get("type"))
                .containsExactly("session.compacted");
        assertThat(fixture.projector.failure()).isNull();
    }

    @Test
    void shouldAbortOnceWhenCommittedWriteFails() {
        Fixture fixture = fixture(false);
        doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.persistence)
                .appendEntryWithUsage(any(), any(), any(), any(), anyList());
        AssistantMessage assistant = assistant(List.of(new TextContent("answer")));

        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        assertThat(fixture.aborts).hasValue(1);
        assertThat(fixture.projector.failure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(fixture.output.committed).isEmpty();
    }

    @Test
    void shouldPauseAskedToolAndResumeSameExecutionInAcceptedSegment() throws Exception {
        Fixture fixture = fixture(false);
        ToolCall call = new ToolCall(
                "call-confirm", "CallMateTool", Map.of("tool", "isolate_port", "args", Map.of("port", 8080)));
        bindAskPermission(fixture.execution, call);
        fixture.execution.installConfirmationHandler(fixture.projector::beforeToolCall);
        AssistantMessage assistant = assistant(List.of(call));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        CompletableFuture<BeforeToolCallResult> confirmation = runBeforeHook(fixture.execution, assistant, call);
        assertThat(fixture.output.closed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.persistedEvents)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("agent.message", "agent.tool_call", "session.status_idle");
        assertThat(fixture.persistedEvents.get(1).getPayload()).contains("\"requiresConfirmation\":true");
        assertThat(fixture.persistedEvents.get(2).getPayload()).contains("\"reason\":\"confirming\"");
        assertThat(confirmation).isNotDone();

        CapturingOutput continuation = new CapturingOutput();
        ExecutionTargetDTO resumed = new ExecutionTargetDTO("session-v2", "execution-v2", "event-root", "segment-2");
        assertThat(fixture.execution.stageContinuationOutput(resumed, continuation))
                .isTrue();
        assertThat(fixture.execution.resumeToolConfirmation(decision(call.id(), resumed)))
                .isTrue();
        assertThat(confirmation.get(1, TimeUnit.SECONDS)).isEqualTo(BeforeToolCallResult.allow());

        fixture.projector.onEvent(new ToolExecutionStartEvent(call.id(), call.name(), call.arguments()));
        fixture.projector.onEvent(new TurnEndEvent(
                assistant,
                List.of(new ToolResultMessage(
                        call.id(), call.name(), List.of(new TextContent("isolated")), null, false, 1L))));
        assertThat(continuation.committed)
                .extracting(event -> event.getData().get("type"))
                .containsExactly("agent.tool_result");
        verify(fixture.persistence).appendEntry(eq(resumed), any(), anyList());
    }

    @Test
    void shouldSnapshotDisplayNameBeforeConfirmationAndReuseCommittedProjection() throws Exception {
        Fixture fixture = fixture(false);
        ToolCall call = new ToolCall("call-confirm", "Read", Map.of("path", "input"));
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("Read");
        when(tool.label()).thenReturn("  Read file  ");
        fixture.execution.bindToolCallSnapshots(RuntimeToolCallSnapshotResolver.from(List.of(tool)));
        bindAskPermission(fixture.execution, call);
        fixture.execution.installConfirmationHandler(fixture.projector::beforeToolCall);
        AssistantMessage assistant = assistant(List.of(call));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));
        when(tool.label()).thenReturn("Renamed later");

        CompletableFuture<BeforeToolCallResult> confirmation = runBeforeHook(fixture.execution, assistant, call);
        assertThat(fixture.output.closed.await(1, TimeUnit.SECONDS)).isTrue();
        CommittedEventDTO persisted = fixture.persistedEvents.get(1);
        RuntimeSseEventVO live = fixture.output.committed.get(1);
        var encoder = new RuntimeEventEncoder(new CommittedEventProjection(new ObjectMapper()), new ObjectMapper());

        assertThat(persisted.getPayload()).contains("\"displayName\":\"Read file\"");
        assertThat(live.getData()).isEqualTo(encoder.committed(persisted).getData());
        fixture.execution.cancelToolConfirmation(new IllegalStateException("test complete"));
        assertThat(confirmation.handle((value, failure) -> failure).get(1, TimeUnit.SECONDS))
                .isNotNull();
    }

    @Test
    void failedMateDisplayResolutionShouldKeepCallAndResult() {
        Fixture fixture = fixture(false);
        MateToolClient client = mock(MateToolClient.class);
        when(client.listAgentTools("agent-1")).thenThrow(new IllegalStateException("metadata unavailable"));
        CallMateTool tool = new MateToolsetFactory(client)
                .createSession("agent-1", Map.of(), MateCredentials.jwt("caller", "token", "access"))
                .createCallTool();
        fixture.execution.bindToolCallSnapshots(RuntimeToolCallSnapshotResolver.from(List.of(tool)));
        ToolCall call = new ToolCall("call-failed", "CallMateTool", Map.of("tool", "Query"));
        AssistantMessage assistant = assistant(List.of(call));
        ToolResultMessage result =
                new ToolResultMessage(call.id(), call.name(), List.of(new TextContent("failed")), null, true, 1L);

        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));
        fixture.projector.onEvent(new TurnEndEvent(assistant, List.of(result)));

        assertThat(fixture.persistedEvents)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("agent.message", "agent.tool_call", "agent.tool_result");
        assertThat(fixture.persistedEvents.get(1).getPayload()).doesNotContain("displayName");
        assertThat(fixture.projector.failure()).isNull();
        verify(client).listAgentTools("agent-1");
    }

    @Test
    void duplicateToolCallIdsDoNotSatisfyExclusivePlanReview() {
        Fixture fixture = fixture(false);
        fixture.execution.applyMode("plan");
        fixture.execution.installConfirmationHandler(fixture.projector::beforeToolCall);
        ToolCall call = new ToolCall("duplicate-exit", "ExitPlanMode", Map.of("plan", "# 方案"));
        AssistantMessage assistant = assistant(List.of(call, call));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        assertThatThrownBy(() -> fixture.execution.beforeToolCall(
                        new BeforeToolCallContext(assistant, call, call.arguments(), null)))
                .isInstanceOf(PlanReviewException.class)
                .satisfies(error -> assertThat(((PlanReviewException) error).stableErrorCode())
                        .isEqualTo("PLAN_REVIEW_UNAVAILABLE"));
        verify(fixture.persistence, never()).markPlanConfirming(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void textPreambleDoesNotCountAsAnotherPlanToolCall() throws Exception {
        Fixture fixture = fixture(false);
        fixture.execution.applyMode("plan");
        fixture.execution.installConfirmationHandler(fixture.projector::beforeToolCall);
        ToolCall call = new ToolCall("single-exit", "ExitPlanMode", Map.of("plan", "# 方案"));
        AssistantMessage assistant = assistant(List.of(new TextContent("请审核以下方案。"), call));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        BeforeToolCallResult result =
                fixture.execution.beforeToolCall(new BeforeToolCallContext(assistant, call, call.arguments(), null));

        assertThat(result).isEqualTo(BeforeToolCallResult.allow());
        verify(fixture.persistence).markPlanConfirming(any(), any(), any(), any(), any(), any(), any());
        fixture.execution.cancelPlanReview(new IllegalStateException("test complete"));
    }

    @Test
    void shouldPersistOriginalQuestionCallBeforeQuestionAndCloseConfirmingSegment() throws Exception {
        Fixture fixture = fixture(false);
        fixture.execution.installConfirmationHandler(fixture.projector::beforeToolCall);
        ToolCall call = new ToolCall(
                "ask-user",
                "AskUserQuestion",
                Map.of("questions", List.of(Map.of("id", "details", "question", "Which details?"))));
        AssistantMessage assistant = assistant(List.of(new TextContent("I need one detail."), call));
        fixture.projector.onEvent(new MessageStartEvent(assistant));
        fixture.projector.onEvent(new MessageEndEvent(assistant));

        BeforeToolCallResult result =
                fixture.execution.beforeToolCall(new BeforeToolCallContext(assistant, call, call.arguments(), null));

        assertThat(result).isEqualTo(BeforeToolCallResult.allow());
        assertThat(fixture.output.closed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.persistedEvents)
                .extracting(CommittedEventDTO::getType)
                .containsExactly("agent.message", "agent.tool_call", "agent.question", "session.status_idle");
        assertThat(fixture.persistedEvents.get(2).getPayload())
                .contains("\"sourceEventId\":\"event-root\"", "\"toolCallId\":\"ask-user\"")
                .contains("\"multiSelect\":false")
                .doesNotContain("\"header\"", "\"options\"");
        verify(fixture.persistence)
                .markQuestionConfirming(
                        eq(fixture.execution.target()), eq("ask-user"), any(), any(), any(), any(), any());
        fixture.execution.cancelQuestion(new IllegalStateException("test complete"));
    }

    private static void bindAskPermission(RuntimeActiveExecution execution, ToolCall call) {
        RuntimeToolPermissionPolicy policy = mock(RuntimeToolPermissionPolicy.class);
        when(policy.decide(call)).thenReturn(RuntimeToolPermissionPolicy.Decision.ASK);
        execution.bindToolPermissions(policy);
    }

    private static CompletableFuture<BeforeToolCallResult> runBeforeHook(
            RuntimeActiveExecution execution, AssistantMessage assistant, ToolCall call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execution.beforeToolCall(new BeforeToolCallContext(assistant, call, call.arguments(), null));
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
    }

    private static ToolConfirmationDecisionDTO decision(String toolCallId, ExecutionTargetDTO target) {
        ToolConfirmationDecisionDTO decision = new ToolConfirmationDecisionDTO();
        decision.setSessionId(target.sessionId());
        decision.setExecutionId(target.executionId());
        decision.setPreviousSegmentId("segment-v2");
        decision.setSegmentId(target.segmentId());
        decision.setToolCallId(toolCallId);
        decision.setResult(ToolConfirmationResult.ALLOW);
        return decision;
    }

    private static Fixture fixture(boolean thinking) {
        return fixture(thinking, 1024L * 1024L);
    }

    private static Fixture fixture(boolean thinking, long maxCommittedEventBytes) {
        ObjectMapper mapper = new ObjectMapper();
        RuntimeSessionRepository repository = mock(RuntimeSessionRepository.class);
        RuntimeExecutionPersistenceService persistence = mock(RuntimeExecutionPersistenceService.class);
        List<CommittedEventDTO> persistedEvents = new ArrayList<>();
        configurePersistence(persistence, persistedEvents);
        RuntimeEntryCodec codec =
                new RuntimeEntryCodec(mapper, new RuntimeMessageSourceConfiguration().messageSource());
        RuntimeCommittedEventFactory events =
                new RuntimeCommittedEventFactory(mapper, new RuntimeMessageSourceConfiguration().messageSource());
        CapturingOutput output = new CapturingOutput();
        RuntimeActiveExecution execution = new RuntimeActiveExecution(output);
        execution.beginRun("execution-v2");
        execution.bindTarget(new ExecutionTargetDTO("session-v2", "execution-v2", "event-root", "segment-v2"));
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        RuntimeEventProjector projector = new RuntimeEventProjector(
                "session-v2",
                "event-root",
                repository,
                persistence,
                codec,
                () -> "entry-" + ids.incrementAndGet(),
                events,
                new RuntimeEventEncoder(new CommittedEventProjection(mapper), mapper),
                Clock.fixed(Instant.parse("2026-09-08T01:02:03.456789Z"), ZoneOffset.UTC),
                maxCommittedEventBytes,
                aborts::incrementAndGet,
                execution,
                new UserMessage("question", 1L),
                thinking,
                Locale.US,
                new RuntimeTodoEventMapper());
        return new Fixture(repository, persistence, codec, execution, projector, output, persistedEvents, aborts);
    }

    private static void configurePersistence(
            RuntimeExecutionPersistenceService persistence, List<CommittedEventDTO> persistedEvents) {
        AtomicInteger sequences = new AtomicInteger();
        when(persistence.appendEntry(any(), any(), anyList())).thenAnswer(invocation -> {
            RuntimeEntryDTO entry = invocation.getArgument(1);
            entry.setEntrySeq(sequences.incrementAndGet());
            persistedEvents.addAll(invocation.getArgument(2));
            return entry;
        });
        when(persistence.appendEntryWithUsage(any(), any(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    RuntimeEntryDTO entry = invocation.getArgument(1);
                    entry.setEntrySeq(sequences.incrementAndGet());
                    persistedEvents.addAll(invocation.getArgument(4));
                    return entry;
                });
        doAnswer(invocation -> {
                    RuntimeEntryDTO toolCall = invocation.getArgument(2);
                    CommittedEventDTO toolCallEvent = invocation.getArgument(3);
                    RuntimeEntryDTO idle = invocation.getArgument(4);
                    CommittedEventDTO idleEvent = invocation.getArgument(5);
                    toolCall.setEntrySeq(sequences.incrementAndGet());
                    idle.setEntrySeq(sequences.incrementAndGet());
                    persistedEvents.add(toolCallEvent);
                    persistedEvents.add(idleEvent);
                    return null;
                })
                .when(persistence)
                .markToolConfirming(any(), any(), any(), any(), any(), any(), any());
        doAnswer(invocation -> {
                    RuntimeEntryDTO plan = invocation.getArgument(2);
                    CommittedEventDTO planEvent = invocation.getArgument(3);
                    RuntimeEntryDTO idle = invocation.getArgument(4);
                    CommittedEventDTO idleEvent = invocation.getArgument(5);
                    plan.setEntrySeq(sequences.incrementAndGet());
                    idle.setEntrySeq(sequences.incrementAndGet());
                    persistedEvents.add(planEvent);
                    persistedEvents.add(idleEvent);
                    return null;
                })
                .when(persistence)
                .markPlanConfirming(any(), any(), any(), any(), any(), any(), any());
        configureQuestionPersistence(persistence, persistedEvents, sequences);
    }

    private static void configureQuestionPersistence(
            RuntimeExecutionPersistenceService persistence,
            List<CommittedEventDTO> persistedEvents,
            AtomicInteger sequences) {
        doAnswer(invocation -> {
                    RuntimeEntryDTO question = invocation.getArgument(2);
                    CommittedEventDTO questionEvent = invocation.getArgument(3);
                    RuntimeEntryDTO idle = invocation.getArgument(4);
                    CommittedEventDTO idleEvent = invocation.getArgument(5);
                    question.setEntrySeq(sequences.incrementAndGet());
                    questionEvent.setEventSeq(sequences.incrementAndGet());
                    idle.setEntrySeq(sequences.incrementAndGet());
                    idleEvent.setEventSeq(sequences.incrementAndGet());
                    persistedEvents.add(questionEvent);
                    persistedEvents.add(idleEvent);
                    return null;
                })
                .when(persistence)
                .markQuestionConfirming(any(), any(), any(), any(), any(), any(), any());
    }

    private static AssistantMessage assistant(List<com.campusclaw.ai.types.ContentBlock> content) {
        return new AssistantMessage(
                content, "openai-responses", "openai", "gpt-test", null, Usage.empty(), StopReason.STOP, null, 1L);
    }

    private record Fixture(
            RuntimeSessionRepository repository,
            RuntimeExecutionPersistenceService persistence,
            RuntimeEntryCodec codec,
            RuntimeActiveExecution execution,
            RuntimeEventProjector projector,
            CapturingOutput output,
            List<CommittedEventDTO> persistedEvents,
            AtomicInteger aborts) {}

    private static final class CapturingOutput implements RuntimeEventOutput {
        private final List<RuntimeSseEventVO> committed = new ArrayList<>();

        private final List<RuntimeSseEventVO> bestEffort = new ArrayList<>();

        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void emit(Supplier<RuntimeSseEventVO> event) {
            committed.add(event.get());
        }

        @Override
        public void emitBestEffort(Supplier<RuntimeSseEventVO> event) {
            bestEffort.add(event.get());
        }

        @Override
        public void complete() {
            closed.countDown();
        }
    }
}
