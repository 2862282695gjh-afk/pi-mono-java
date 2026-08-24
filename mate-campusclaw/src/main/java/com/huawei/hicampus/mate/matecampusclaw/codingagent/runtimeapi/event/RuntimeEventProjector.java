/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.MessageUpdateEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionUpdateEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.TurnEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.ai.stream.AssistantMessageEvent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeEntryDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionStartedEvent;

/**
 * 把 pi AgentEvent 和公共 Session 压缩事件投影为公共 SSE 与持久化 Entry。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeEventProjector {
    private static final int ENTRY_BATCH_SIZE = 500;

    private final String sessionId;

    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator idGenerator;

    private final RuntimeEventStream stream;

    private final Clock clock;

    private final Runnable abort;

    private final RuntimeActiveExecution execution;

    private final UserMessage initialUserMessage;

    private final boolean thinking;

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private String assistantEntryId;

    private StopReason terminalReason = StopReason.STOP;

    public RuntimeEventProjector(
            String sessionId,
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator idGenerator,
            RuntimeEventStream stream,
            Clock clock,
            Runnable abort,
            RuntimeActiveExecution execution,
            UserMessage initialUserMessage,
            boolean thinking) {
        this.sessionId = sessionId;
        this.repository = repository;
        this.codec = codec;
        this.idGenerator = idGenerator;
        this.stream = stream;
        this.clock = clock;
        this.abort = abort;
        this.execution = execution;
        this.initialUserMessage = initialUserMessage;
        this.thinking = thinking;
    }

    public synchronized void onEvent(AgentEvent event) {
        if (failure.get() != null) {
            return;
        }
        try {
            project(event);
        } catch (RuntimeException error) {
            if (failure.compareAndSet(null, error)) {
                abort.run();
            }
        }
    }

    public Throwable failure() {
        return failure.get();
    }

    public StopReason terminalReason() {
        return terminalReason;
    }

    private void project(AgentEvent event) {
        switch (event) {
            case MessageStartEvent start -> projectMessageStart(start);
            case MessageUpdateEvent update -> projectMessageUpdate(update);
            case MessageEndEvent end -> projectMessageEnd(end);
            case ToolExecutionStartEvent start -> projectToolStart(start);
            case ToolExecutionUpdateEvent update -> projectToolUpdate(update);
            case ToolExecutionEndEvent end -> projectToolEnd(end);
            case TurnEndEvent end -> projectToolResults(end.toolResults());
            default -> {}
        }
    }

    public synchronized void onCompactionEvent(SessionCompactionEvent event) {
        if (failure.get() != null) {
            return;
        }
        try {
            switch (event) {
                case SessionCompactionStartedEvent started -> projectCompactionStarted(started);
                case SessionCompactionCompletedEvent completed -> projectCompactionCompleted(completed);
                case SessionCompactionFailedEvent failed -> projectCompactionFailed(failed);
            }
        } catch (RuntimeException error) {
            if (failure.compareAndSet(null, error)) {
                abort.run();
            }
        }
    }

    private void projectMessageStart(MessageStartEvent event) {
        if (!(event.message() instanceof AssistantMessage)) {
            return;
        }
        assistantEntryId = idGenerator.nextId();
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("entryId", assistantEntryId);
        data.put("role", "assistant");
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.ASSISTANT_MESSAGE_STARTED.value(), data));
    }

    private void projectMessageUpdate(MessageUpdateEvent event) {
        if (assistantEntryId == null) {
            return;
        }
        AssistantMessageEvent messageEvent = event.assistantMessageEvent();
        if (messageEvent instanceof AssistantMessageEvent.TextDeltaEvent delta) {
            LinkedHashMap<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", delta.delta());
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("entryId", assistantEntryId);
            data.put("delta", block);
            stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.ASSISTANT_MESSAGE_DELTA.value(), data));
        } else if (thinking) {
            projectThinking(messageEvent);
        }
    }

    private void projectThinking(AssistantMessageEvent event) {
        switch (event) {
            case AssistantMessageEvent.ThinkingStartEvent start -> emitThinkingStarted(start.contentIndex());
            case AssistantMessageEvent.ThinkingDeltaEvent delta -> emitThinkingDelta(delta);
            case AssistantMessageEvent.ThinkingEndEvent end -> persistThinking(end);
            default -> {}
        }
    }

    private void emitThinkingStarted(int contentIndex) {
        LinkedHashMap<String, Object> data = thinkingData(contentIndex);
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.ASSISTANT_THINKING_STARTED.value(), data));
    }

    private void emitThinkingDelta(AssistantMessageEvent.ThinkingDeltaEvent event) {
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        block.put("type", "thinking");
        block.put("text", event.delta());
        LinkedHashMap<String, Object> data = thinkingData(event.contentIndex());
        data.put("delta", block);
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.ASSISTANT_THINKING_DELTA.value(), data));
    }

    private void persistThinking(AssistantMessageEvent.ThinkingEndEvent event) {
        RuntimeEntryDTO entry = codec.thinkingEntry(
                sessionId, idGenerator.nextId(), assistantEntryId, event.contentIndex(), event.content(), now());
        repository.appendEntry(entry);
        stream.emit(new RuntimeSseEventVO(Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
    }

    private LinkedHashMap<String, Object> thinkingData(int contentIndex) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("assistantEntryId", assistantEntryId);
        data.put("contentIndex", contentIndex);
        return data;
    }

    private void projectMessageEnd(MessageEndEvent event) {
        if (event.message() instanceof AssistantMessage assistant) {
            persistAssistant(assistant);
        } else if (event.message() instanceof UserMessage user) {
            persistQueuedUser(user);
        }
    }

    private void persistAssistant(AssistantMessage message) {
        String entryId = assistantEntryId != null ? assistantEntryId : idGenerator.nextId();
        RuntimeEntryDTO entry = codec.assistantEntry(sessionId, entryId, message, now());
        repository.appendEntry(entry);
        stream.emit(new RuntimeSseEventVO(Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
        assistantEntryId = null;
        terminalReason = message.stopReason();
    }

    private void persistQueuedUser(UserMessage message) {
        if (message == initialUserMessage) {
            return;
        }
        execution.controlDelivered(message);
        String text = message.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
        RuntimeEntryDTO entry = codec.userEntry(sessionId, idGenerator.nextId(), text, List.of(), now());
        repository.appendEntry(entry);
        stream.emit(new RuntimeSseEventVO(Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
    }

    private void projectToolStart(ToolExecutionStartEvent event) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", event.toolCallId());
        data.put("toolName", event.toolName());
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.TOOL_EXECUTION_STARTED.value(), data));
    }

    private void projectToolUpdate(ToolExecutionUpdateEvent event) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", event.toolCallId());
        data.put("toolName", event.toolName());
        data.put("delta", event.partialResult());
        stream.emitBestEffort(new RuntimeSseEventVO(null, RuntimeEventType.TOOL_EXECUTION_DELTA.value(), data));
    }

    private void projectToolEnd(ToolExecutionEndEvent event) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", event.toolCallId());
        data.put("toolName", event.toolName());
        data.put("isError", event.isError());
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.TOOL_EXECUTION_COMPLETED.value(), data));
    }

    private void projectToolResults(List<ToolResultMessage> results) {
        for (ToolResultMessage result : results) {
            RuntimeEntryDTO entry = codec.toolResultEntry(sessionId, idGenerator.nextId(), result, now());
            repository.appendEntry(entry);
            stream.emit(
                    new RuntimeSseEventVO(Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
        }
    }

    private void projectCompactionStarted(SessionCompactionStartedEvent event) {
        LinkedHashMap<String, Object> data =
                compactionLifecycleData(event.reason().value(), event.willRetry());
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.SESSION_COMPACTION_STARTED.value(), data));
    }

    private void projectCompactionFailed(SessionCompactionFailedEvent event) {
        LinkedHashMap<String, Object> data =
                compactionLifecycleData(event.reason().value(), event.willRetry());
        data.put("aborted", event.aborted());
        data.put("message", event.message());
        stream.emit(new RuntimeSseEventVO(null, RuntimeEventType.SESSION_COMPACTION_FAILED.value(), data));
    }

    private void projectCompactionCompleted(SessionCompactionCompletedEvent event) {
        List<RuntimeEntryDTO> entries = loadCurrentBranch();
        List<String> contextIds = codec.toAgentContextEntryIds(entries);
        int firstKeptIndex = event.result().compactedMessageCount();
        if (firstKeptIndex < 0 || firstKeptIndex >= contextIds.size()) {
            throw new IllegalStateException("compaction retained boundary is not present in runtime history");
        }
        RuntimeEntryDTO entry = codec.compactionEntry(
                sessionId,
                idGenerator.nextId(),
                event.reason(),
                contextIds.get(firstKeptIndex),
                event.result(),
                event.willRetry(),
                now());
        repository.appendEntry(entry);
        stream.emit(new RuntimeSseEventVO(Long.toString(entry.getEntrySeq()), entry.getType(), codec.toSseData(entry)));
    }

    private List<RuntimeEntryDTO> loadCurrentBranch() {
        List<RuntimeEntryDTO> entries = new java.util.ArrayList<>();
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

    private static LinkedHashMap<String, Object> compactionLifecycleData(String reason, boolean willRetry) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("willRetry", willRetry);
        return data;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
