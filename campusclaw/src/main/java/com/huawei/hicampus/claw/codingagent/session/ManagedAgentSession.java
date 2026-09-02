/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.huawei.hicampus.claw.agent.Agent;
import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.session.compaction.AutomaticCompactionDecision;
import com.huawei.hicampus.claw.codingagent.session.compaction.AutomaticCompactionDecision.Action;
import com.huawei.hicampus.claw.codingagent.session.compaction.CompactionReason;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionResult;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactionStartedEvent;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactor;
import com.huawei.hicampus.claw.codingagent.session.compaction.SessionCompactor.PreparedCompaction;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;

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

    private static final String RETRY_EXHAUSTED_MESSAGE =
            "Context overflow recovery failed after one compact-and-retry attempt";

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
        return compactBeforePrompt()
                .thenCompose(ignored -> agent.prompt(message))
                .thenCompose(ignored -> compactAfterExecution(false));
    }

    public CompletableFuture<Void> continueQueuedExecution() {
        return agent.continueQueuedExecution().thenCompose(ignored -> compactAfterExecution(false));
    }

    public CompletableFuture<SessionCompactionResult> compact(String customInstructions) {
        agent.abort();
        return agent.waitForIdle().thenCompose(ignored -> manualCompaction(customInstructions));
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

    private CompletableFuture<Void> compactBeforePrompt() {
        List<Message> messages = List.copyOf(agent.getState().getMessages());
        AutomaticCompactionDecision decision =
                compactor.decide(messages, agent.getState().getModel(), true);
        if (!decision.requiresCompaction()) {
            return CompletableFuture.completedFuture(null);
        }
        List<Message> compactable = decision.willRetry() ? withoutLastAssistant(messages) : messages;
        return automaticCompaction(decision, compactable).handle((result, error) -> null);
    }

    private CompletableFuture<Void> compactAfterExecution(boolean recoveryAttempted) {
        List<Message> messages = List.copyOf(agent.getState().getMessages());
        AutomaticCompactionDecision decision =
                compactor.decide(messages, agent.getState().getModel(), false);
        if (!decision.requiresCompaction()) {
            return CompletableFuture.completedFuture(null);
        }
        if (decision.action() == Action.OVERFLOW_RETRY && recoveryAttempted) {
            emitCompaction(new SessionCompactionFailedEvent(CompactionReason.OVERFLOW, false, RETRY_EXHAUSTED_MESSAGE));
            return CompletableFuture.completedFuture(null);
        }
        List<Message> compactable = decision.willRetry() ? withoutLastAssistant(messages) : messages;
        return automaticCompaction(decision, compactable)
                .handle((result, error) -> error == null && result != null)
                .thenCompose(compacted -> continueAfterCompaction(decision, compacted));
    }

    private CompletableFuture<Void> continueAfterCompaction(AutomaticCompactionDecision decision, boolean compacted) {
        if (!compacted || !decision.willRetry()) {
            return CompletableFuture.completedFuture(null);
        }
        return agent.continueExecution().thenCompose(ignored -> compactAfterExecution(true));
    }

    private CompletableFuture<SessionCompactionResult> automaticCompaction(
            AutomaticCompactionDecision decision, List<Message> messages) {
        PreparedCompaction prepared = compactor.prepare(messages);
        if (prepared == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompactionReason reason =
                decision.action() == Action.THRESHOLD ? CompactionReason.THRESHOLD : CompactionReason.OVERFLOW;
        return runPreparedCompaction(reason, decision.willRetry(), prepared, null);
    }

    private CompletableFuture<SessionCompactionResult> manualCompaction(String customInstructions) {
        PreparedCompaction prepared =
                compactor.prepare(List.copyOf(agent.getState().getMessages()));
        if (prepared == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Session has no compactable history"));
        }
        return runPreparedCompaction(CompactionReason.MANUAL, false, prepared, customInstructions);
    }

    private CompletableFuture<SessionCompactionResult> runPreparedCompaction(
            CompactionReason reason, boolean willRetry, PreparedCompaction prepared, String customInstructions) {
        emitCompaction(new SessionCompactionStartedEvent(reason, willRetry));
        CompletableFuture<SessionCompactionResult> future;
        try {
            future = compactor.compact(
                    prepared, agent.getState().getModel(), agent.getState().getThinkingLevel(), customInstructions);
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
            emitCompaction(
                    new SessionCompactionFailedEvent(reason, willRetry, isCancellation(error), "compaction failed"));
            return;
        }
        agent.replaceMessages(compactor.compactedMessages(result));
        emitCompaction(new SessionCompactionCompletedEvent(reason, result, willRetry));
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

    private static boolean isCancellation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }
}
