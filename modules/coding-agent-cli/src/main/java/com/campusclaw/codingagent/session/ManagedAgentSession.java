/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.campusclaw.agent.Agent;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.session.compaction.CompactionReason;
import com.campusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.campusclaw.codingagent.session.compaction.SessionCompactionStartedEvent;
import com.campusclaw.codingagent.session.compaction.SessionCompactor;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表示由三个入口共同使用的轻量 Agent Session 实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ManagedAgentSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ManagedAgentSession.class);

    private final PreparedAgentRuntime runtime;

    private final ToolEntryPoint entryPoint;

    private final Agent agent;

    private final List<AgentTool> tools;

    private final SessionCompactor compactor;

    private final CopyOnWriteArrayList<Consumer<SessionCompactionEvent>> compactionListeners =
            new CopyOnWriteArrayList<>();

    private volatile CompletableFuture<SessionCompactionResult> currentCompaction;

    ManagedAgentSession(
            PreparedAgentRuntime runtime,
            ToolEntryPoint entryPoint,
            Agent agent,
            List<AgentTool> tools,
            SessionCompactor compactor) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.entryPoint = Objects.requireNonNull(entryPoint, "entryPoint");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.tools = List.copyOf(tools);
        this.compactor = Objects.requireNonNull(compactor, "compactor");
    }

    public PreparedAgentRuntime runtime() {
        return runtime;
    }

    public ToolEntryPoint entryPoint() {
        return entryPoint;
    }

    public Agent agent() {
        return agent;
    }

    public List<AgentTool> tools() {
        return tools;
    }

    public CompletableFuture<Void> prompt(String message) {
        return prompt(new UserMessage(message, System.currentTimeMillis()));
    }

    public CompletableFuture<Void> prompt(Message message) {
        return agent.prompt(message).thenCompose(ignored -> compactAfterExecution(false));
    }

    public CompletableFuture<Void> continueQueuedExecution() {
        return agent.continueQueuedExecution().thenCompose(ignored -> compactAfterExecution(false));
    }

    public CompletableFuture<SessionCompactionResult> compact(String customInstructions) {
        return runCompaction(
                CompactionReason.MANUAL, false, List.copyOf(agent.getState().getMessages()), customInstructions);
    }

    public Runnable subscribeCompaction(Consumer<SessionCompactionEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        compactionListeners.add(listener);
        return () -> compactionListeners.remove(listener);
    }

    @Override
    public void close() {
        abort();
    }

    public void abort() {
        CompletableFuture<SessionCompactionResult> compaction = currentCompaction;
        if (compaction != null) {
            compaction.cancel(true);
        }
        agent.abort();
        agent.clearSteeringQueue();
        agent.clearFollowUpQueue();
    }

    private CompletableFuture<Void> compactAfterExecution(boolean recoveryAttempted) {
        if (recoveryAttempted) {
            return CompletableFuture.completedFuture(null);
        }
        List<Message> messages = List.copyOf(agent.getState().getMessages());
        if (compactor.isOverflow(messages, agent.getState().getModel())) {
            List<Message> retryMessages = withoutLastAssistant(messages);
            return runCompaction(CompactionReason.OVERFLOW, true, retryMessages, null)
                    .handle((result, error) -> error == null)
                    .thenCompose(compacted -> compacted
                            ? agent.continueExecution().thenCompose(ignored -> compactAfterExecution(true))
                            : CompletableFuture.completedFuture(null));
        }
        if (compactor.exceedsThreshold(messages, agent.getState().getModel())) {
            return runCompaction(CompactionReason.THRESHOLD, false, messages, null)
                    .handle((result, error) -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<SessionCompactionResult> runCompaction(
            CompactionReason reason, boolean willRetry, List<Message> messages, String customInstructions) {
        emitCompaction(new SessionCompactionStartedEvent(reason, willRetry));
        CompletableFuture<SessionCompactionResult> future;
        try {
            future = compactor.compact(
                    messages, agent.getState().getModel(), agent.getState().getThinkingLevel(), customInstructions);
        } catch (RuntimeException error) {
            emitCompaction(new SessionCompactionFailedEvent(reason, willRetry, "compaction failed"));
            return CompletableFuture.failedFuture(error);
        }
        currentCompaction = future;
        return future.whenComplete((result, error) -> finishCompaction(reason, willRetry, result, error));
    }

    private void finishCompaction(
            CompactionReason reason, boolean willRetry, SessionCompactionResult result, Throwable error) {
        currentCompaction = null;
        if (error != null) {
            emitCompaction(new SessionCompactionFailedEvent(reason, willRetry, "compaction failed"));
            return;
        }
        applyCompaction(result);
        emitCompaction(new SessionCompactionCompletedEvent(reason, result, willRetry));
    }

    private void applyCompaction(SessionCompactionResult result) {
        List<Message> compacted = new java.util.ArrayList<>();
        compacted.add(new UserMessage("[Context compaction summary]\n" + result.summary(), System.currentTimeMillis()));
        compacted.addAll(result.retainedMessages());
        agent.replaceMessages(compacted);
    }

    private void emitCompaction(SessionCompactionEvent event) {
        for (Consumer<SessionCompactionEvent> listener : compactionListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException error) {
                log.warn(
                        "Session compaction listener failed (event={})",
                        event.getClass().getSimpleName(),
                        error);
            }
        }
    }

    private static List<Message> withoutLastAssistant(List<Message> messages) {
        if (!messages.isEmpty() && messages.getLast() instanceof AssistantMessage) {
            return List.copyOf(messages.subList(0, messages.size() - 1));
        }
        return messages;
    }
}
