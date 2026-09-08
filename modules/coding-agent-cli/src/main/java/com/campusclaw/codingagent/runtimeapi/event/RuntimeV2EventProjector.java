/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.campusclaw.agent.event.AgentEndEvent;
import com.campusclaw.agent.event.AgentEvent;
import com.campusclaw.agent.event.MessageEndEvent;
import com.campusclaw.agent.event.MessageStartEvent;
import com.campusclaw.agent.event.MessageUpdateEvent;
import com.campusclaw.agent.event.ToolExecutionStartEvent;
import com.campusclaw.agent.event.TurnEndEvent;
import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.BeforeToolCallResult;
import com.campusclaw.ai.stream.AssistantMessageEvent;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeRecordDTO;
import com.campusclaw.codingagent.runtimeapi.dto.ToolConfirmationDecisionDTO;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolCallDeniedException;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.campusclaw.codingagent.runtimeapi.session.ToolConfirmationResult;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentMessageResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentThinkingResponseVO;
import com.campusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把单个 v2 执行段的 Agent 与自动压缩事件提交为权威公共事件，并发送同形 SSE 帧。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeV2EventProjector {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeV2EventProjector.class);

    private static final int ENTRY_BATCH_SIZE = 500;

    private final String sessionId;

    private final String sourceEventId;

    private final RuntimeSessionRepository repository;

    private final RuntimeExecutionPersistenceService persistence;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeCommittedEventFactory events;

    private final RuntimeV2EventEncoder encoder;

    private final Clock clock;

    private final long maxCommittedEventBytes;

    private final Runnable abort;

    private final RuntimeActiveExecution execution;

    private final UserMessage initialUserMessage;

    private final boolean thinking;

    private final Locale locale;

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private final Map<Integer, String> thinkingEntryIds = new LinkedHashMap<>();

    private final Map<String, String> toolCallEntryIds = new LinkedHashMap<>();

    private final Map<String, ToolCall> finalizedToolCalls = new LinkedHashMap<>();

    private String assistantEntryId;

    private StopReason terminalReason = StopReason.STOP;

    private String terminalErrorCode;

    private int assistantAttempt = 1;

    private Long lastCompactionEntrySeq;

    public RuntimeV2EventProjector(
            String sessionId,
            String sourceEventId,
            RuntimeSessionRepository repository,
            RuntimeExecutionPersistenceService persistence,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeCommittedEventFactory events,
            RuntimeV2EventEncoder encoder,
            Clock clock,
            long maxCommittedEventBytes,
            Runnable abort,
            RuntimeActiveExecution execution,
            UserMessage initialUserMessage,
            boolean thinking,
            Locale locale) {
        this.sessionId = sessionId;
        this.sourceEventId = sourceEventId;
        this.repository = repository;
        this.persistence = persistence;
        this.codec = codec;
        this.ids = ids;
        this.events = events;
        this.encoder = encoder;
        this.clock = clock;
        this.maxCommittedEventBytes = maxCommittedEventBytes;
        this.abort = abort;
        this.execution = execution;
        this.initialUserMessage = initialUserMessage;
        this.thinking = thinking;
        this.locale = locale;
    }

    public synchronized void onEvent(AgentEvent event) {
        if (failure.get() == null) {
            projectSafely(() -> project(event));
        }
    }

    public synchronized void onCompactionEvent(SessionCompactionEvent event) {
        if (failure.get() == null && event instanceof SessionCompactionCompletedEvent completed) {
            projectSafely(() -> projectCompaction(completed));
        }
    }

    public Throwable failure() {
        return failure.get();
    }

    public StopReason terminalReason() {
        return terminalReason;
    }

    public String terminalErrorCode() {
        return terminalErrorCode;
    }

    public synchronized Long lastCompactionEntrySeq() {
        return lastCompactionEntrySeq;
    }

    public BeforeToolCallResult beforeToolCall(BeforeToolCallContext context) throws Exception {
        var pending = beginConfirmation(context.toolCall());
        ToolConfirmationDecisionDTO decision = pending.get();
        if (decision.getResult() == ToolConfirmationResult.ALLOW) {
            return BeforeToolCallResult.allow();
        }
        if (decision.getResult() == ToolConfirmationResult.DENY) {
            throw new RuntimeToolCallDeniedException();
        }
        throw new IllegalStateException("tool confirmation result is missing");
    }

    private void projectSafely(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException error) {
            recordFailure(error);
        }
    }

    private void project(AgentEvent event) {
        switch (event) {
            case AgentEndEvent end -> projectAgentEnd(end);
            case MessageStartEvent start -> projectMessageStart(start);
            case MessageUpdateEvent update -> projectMessageUpdate(update);
            case MessageEndEvent end -> projectMessageEnd(end);
            case ToolExecutionStartEvent start -> persistToolCall(start);
            case TurnEndEvent end -> persistToolResults(end.toolResults());
            default -> {}
        }
    }

    private void projectAgentEnd(AgentEndEvent event) {
        if (event.cancelled()) {
            terminalReason = StopReason.ABORTED;
        }
    }

    private void projectMessageStart(MessageStartEvent event) {
        if (event.message() instanceof AssistantMessage) {
            assistantEntryId = ids.nextId();
            thinkingEntryIds.clear();
            finalizedToolCalls.clear();
        }
    }

    private void projectMessageUpdate(MessageUpdateEvent event) {
        if (assistantEntryId == null) {
            return;
        }
        AssistantMessageEvent update = event.assistantMessageEvent();
        if (update instanceof AssistantMessageEvent.TextDeltaEvent delta) {
            emitMessageDelta(delta.delta());
        } else if (thinking) {
            projectThinking(update);
        }
    }

    private void emitMessageDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        var response = new SessionEventResponseVO(
                assistantEntryId,
                CommittedEventType.AGENT_MESSAGE.value(),
                null,
                new AgentMessageResponseVO("delta", content, sourceEventId, null));
        execution.output().emitBestEffort(() -> encoder.response(response));
    }

    private void projectThinking(AssistantMessageEvent update) {
        if (update instanceof AssistantMessageEvent.ThinkingDeltaEvent delta && delta.publicSummary()) {
            emitThinkingDelta(delta.contentIndex(), delta.delta());
        } else if (update instanceof AssistantMessageEvent.ThinkingEndEvent end && end.publicSummary()) {
            persistThinking(end);
        }
    }

    private void emitThinkingDelta(int contentIndex, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        String eventId = thinkingEntryIds.computeIfAbsent(contentIndex, ignored -> ids.nextId());
        var response = new SessionEventResponseVO(
                eventId,
                CommittedEventType.AGENT_THINKING.value(),
                null,
                new AgentThinkingResponseVO("delta", content, sourceEventId));
        execution.output().emitBestEffort(() -> encoder.response(response));
    }

    private void persistThinking(AssistantMessageEvent.ThinkingEndEvent event) {
        String entryId = thinkingEntryIds.computeIfAbsent(event.contentIndex(), ignored -> ids.nextId());
        RuntimeEntryDTO entry =
                codec.thinkingEntry(sessionId, entryId, assistantEntryId, event.contentIndex(), event.content(), now());
        CommittedEventDTO committed = events.agentThinking(entry, entryId, event.content(), sourceEventId);
        RuntimeSseEventVO frame = requireCommittedFrame(committed);
        persistence.appendEntry(execution.target(), entry, List.of(committed));
        execution.output().emit(() -> frame);
    }

    private void projectMessageEnd(MessageEndEvent event) {
        if (event.message() instanceof AssistantMessage assistant) {
            persistAssistant(assistant);
        } else if (event.message() instanceof UserMessage user && user != initialUserMessage) {
            persistQueuedUser(user);
        }
    }

    private void persistAssistant(AssistantMessage message) {
        String entryId = assistantEntryId != null ? assistantEntryId : ids.nextId();
        RuntimeEntryDTO entry = codec.assistantEntry(sessionId, entryId, message, now());
        RuntimeRecordDTO record = codec.usageRecord(
                sessionId,
                ids.nextId(),
                execution.runId(),
                RuntimeUsageCause.ASSISTANT,
                entryId,
                assistantAttempt,
                message.stopReason(),
                message.usage(),
                entry.getTimestamp());
        CommittedEventDTO committed = events.agentMessage(entry, entryId, message, sourceEventId);
        RuntimeSseEventVO frame = requireCommittedFrame(committed);
        persistence.appendEntryWithUsage(execution.target(), entry, record, message.usage(), List.of(committed));
        execution.output().emit(() -> frame);
        persistFinalizedToolCalls(message);
        terminalReason = message.stopReason();
        terminalErrorCode = message.errorCode();
        assistantEntryId = null;
        thinkingEntryIds.clear();
    }

    private void persistQueuedUser(UserMessage message) {
        throw new IllegalStateException("v2 execution does not accept queued user messages");
    }

    private void persistToolCall(ToolExecutionStartEvent event) {
        if (toolCallEntryIds.containsKey(event.toolCallId())) {
            return;
        }
        Map<String, Object> arguments = requireArguments(event.args());
        persistToolCall(new ToolCall(event.toolCallId(), event.toolName(), arguments), false);
    }

    private void persistFinalizedToolCalls(AssistantMessage message) {
        message.content().stream()
                .filter(ToolCall.class::isInstance)
                .map(ToolCall.class::cast)
                .forEach(this::persistFinalizedToolCall);
    }

    private void persistFinalizedToolCall(ToolCall call) {
        finalizedToolCalls.put(call.id(), call);
        if (execution.toolPermission(call) != RuntimeToolPermissionPolicy.Decision.ASK) {
            persistToolCall(call, false);
        }
    }

    private void persistToolCall(ToolCall call, boolean requiresConfirmation) {
        if (toolCallEntryIds.containsKey(call.id())) {
            return;
        }
        String entryId = ids.nextId();
        RuntimeEntryDTO entry = codec.toolCallEntry(
                sessionId, entryId, call.id(), call.name(), call.arguments(), requiresConfirmation, now());
        CommittedEventDTO committed = events.agentToolCall(entry, entryId, call, requiresConfirmation, sourceEventId);
        RuntimeSseEventVO frame = requireCommittedFrame(committed);
        persistence.appendEntry(execution.target(), entry, List.of(committed));
        toolCallEntryIds.put(call.id(), entryId);
        execution.output().emit(() -> frame);
    }

    private static Map<String, Object> requireArguments(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("tool arguments must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("tool argument names must be strings");
            }
            result.put(name, item);
        });
        return Collections.unmodifiableMap(result);
    }

    private void persistToolResults(List<ToolResultMessage> results) {
        for (ToolResultMessage result : results) {
            persistMissingToolCall(result.toolCallId());
            RuntimeEntryDTO entry = codec.toolResultEntry(sessionId, ids.nextId(), result, now());
            CommittedEventDTO committed = events.agentToolResult(entry, entry.getId(), result, sourceEventId, locale);
            RuntimeSseEventVO frame = requireCommittedFrame(committed);
            persistence.appendEntry(execution.target(), entry, List.of(committed));
            execution.output().emit(() -> frame);
        }
    }

    private void persistMissingToolCall(String toolCallId) {
        if (toolCallEntryIds.containsKey(toolCallId)) {
            return;
        }
        ToolCall call = finalizedToolCalls.get(toolCallId);
        if (call == null) {
            throw new IllegalStateException("tool result has no finalized tool call");
        }
        boolean requiresConfirmation = execution.toolPermission(call) == RuntimeToolPermissionPolicy.Decision.ASK;
        persistToolCall(call, requiresConfirmation);
    }

    private synchronized java.util.concurrent.CompletableFuture<ToolConfirmationDecisionDTO> beginConfirmation(
            ToolCall call) {
        ToolCall finalized = finalizedToolCalls.get(call.id());
        if (finalized == null || !finalized.equals(call)) {
            throw new IllegalStateException("tool confirmation does not match the finalized tool call");
        }
        var pending = execution.beginToolConfirmation(call.id());
        try {
            persistConfirming(finalized);
            return pending;
        } catch (RuntimeException error) {
            execution.cancelToolConfirmation(error);
            recordFailure(error);
            throw error;
        }
    }

    private void persistConfirming(ToolCall call) {
        OffsetDateTime confirmingAt = now();
        RuntimeEntryDTO toolCall = codec.toolCallEntry(
                sessionId, ids.nextId(), call.id(), call.name(), call.arguments(), true, confirmingAt);
        CommittedEventDTO toolCallEvent = events.agentToolCall(toolCall, toolCall.getId(), call, true, sourceEventId);
        RuntimeEntryDTO idle =
                codec.sessionIdleEntry(sessionId, ids.nextId(), "confirming", sourceEventId, null, confirmingAt);
        CommittedEventDTO idleEvent = events.sessionIdle(idle, idle.getId(), "confirming", sourceEventId, null, locale);
        RuntimeEventOutput output = execution.output();
        RuntimeSseEventVO toolCallFrame = requireCommittedFrame(toolCallEvent);
        RuntimeSseEventVO idleFrame = requireCommittedFrame(idleEvent);
        persistence.markToolConfirming(
                execution.target(), call.id(), toolCall, toolCallEvent, idle, idleEvent, confirmingAt);
        toolCallEntryIds.put(call.id(), toolCall.getId());
        finishConfirmingOutput(output, toolCallFrame, idleFrame);
    }

    private static void finishConfirmingOutput(
            RuntimeEventOutput output, RuntimeSseEventVO toolCall, RuntimeSseEventVO idle) {
        try {
            output.emit(() -> toolCall);
            output.emit(() -> idle);
        } catch (RuntimeException error) {
            // 权威事件已提交；响应故障只影响本连接，确认决定仍可跨实例恢复。
            LOGGER.debug("Unable to emit committed confirming events", error);
        } finally {
            try {
                output.complete();
            } catch (RuntimeException error) {
                // 响应资源无法继续使用，不改变已经提交的 confirming 状态。
                LOGGER.debug("Unable to complete confirming response", error);
            }
        }
    }

    private void projectCompaction(SessionCompactionCompletedEvent event) {
        List<RuntimeEntryDTO> entries = loadCurrentBranch();
        List<String> contextIds = codec.toAgentContextEntryIds(entries);
        int firstKeptIndex = event.result().compactedMessageCount();
        if (firstKeptIndex < 0 || firstKeptIndex >= contextIds.size()) {
            throw new IllegalStateException("compaction retained boundary is not present in runtime history");
        }
        RuntimeEntryDTO entry = createCompactionEntry(event, entries, contextIds.get(firstKeptIndex));
        RuntimeRecordDTO record = codec.usageRecord(
                sessionId,
                ids.nextId(),
                execution.runId(),
                RuntimeUsageCause.COMPACTION,
                entry.getId(),
                assistantAttempt,
                null,
                event.result().usage(),
                entry.getTimestamp());
        CommittedEventDTO committed = events.sessionCompacted(
                entry,
                entry.getId(),
                event.reason().value(),
                event.result().tokensBefore(),
                event.result().estimatedTokensAfter(),
                sourceEventId);
        RuntimeSseEventVO frame = requireCommittedFrame(committed);
        RuntimeEntryDTO persisted = persistence.appendEntryWithUsage(
                execution.target(), entry, record, event.result().usage(), List.of(committed));
        lastCompactionEntrySeq = persisted.getEntrySeq();
        execution.output().emit(() -> frame);
        if (event.willRetry()) {
            assistantAttempt++;
        }
    }

    private RuntimeEntryDTO createCompactionEntry(
            SessionCompactionCompletedEvent event, List<RuntimeEntryDTO> entries, String firstKeptEntryId) {
        return codec.compactionEntry(
                sessionId,
                ids.nextId(),
                event.reason(),
                firstKeptEntryId,
                discardedEntryId(entries, event.willRetry()),
                event.result(),
                event.willRetry(),
                now());
    }

    private String discardedEntryId(List<RuntimeEntryDTO> entries, boolean willRetry) {
        if (!willRetry) {
            return null;
        }
        String entryId = codec.lastRetriableAssistantEntryId(entries);
        if (entryId == null) {
            throw new IllegalStateException("compaction retry candidate is not present in runtime history");
        }
        return entryId;
    }

    private List<RuntimeEntryDTO> loadCurrentBranch() {
        List<RuntimeEntryDTO> entries = new ArrayList<>();
        long afterSeq = 0L;
        while (true) {
            List<RuntimeEntryDTO> batch = repository.listCurrentBranchEntries(sessionId, afterSeq, ENTRY_BATCH_SIZE);
            entries.addAll(batch);
            if (batch.size() < ENTRY_BATCH_SIZE) {
                return List.copyOf(entries);
            }
            afterSeq = batch.getLast().getEntrySeq();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private RuntimeSseEventVO requireCommittedFrame(CommittedEventDTO event) {
        RuntimeSseEventVO frame = encoder.committed(event);
        if (codec.encodedSseBytes(frame) <= maxCommittedEventBytes) {
            return frame;
        }
        terminalReason = StopReason.ERROR;
        terminalErrorCode = "EVENT_PAYLOAD_TOO_LARGE";
        throw new IllegalStateException("committed event exceeds the v2 stream capacity");
    }

    private void recordFailure(RuntimeException error) {
        if (failure.compareAndSet(null, error)) {
            abort.run();
        }
    }
}
