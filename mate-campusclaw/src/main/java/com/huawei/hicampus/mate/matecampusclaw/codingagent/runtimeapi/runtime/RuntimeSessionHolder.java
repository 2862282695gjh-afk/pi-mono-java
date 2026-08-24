/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.ManagedAgentSession;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionEvent;

/**
 * 单个 Runtime Session 的进程内执行对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeSessionHolder {
    private final String sessionId;

    private final AgentDirectorySnapshotDTO snapshot;

    private final Agent agent;

    private final ManagedAgentSession managedSession;

    private final boolean thinking;

    private final AtomicReference<RuntimeActiveExecution> activeExecution = new AtomicReference<>();

    public RuntimeSessionHolder(String sessionId, AgentDirectorySnapshotDTO snapshot, Agent agent, boolean thinking) {
        this(sessionId, snapshot, agent, null, thinking);
    }

    public RuntimeSessionHolder(
            String sessionId, AgentDirectorySnapshotDTO snapshot, ManagedAgentSession session, boolean thinking) {
        this(sessionId, snapshot, session.agent(), session, thinking);
    }

    private RuntimeSessionHolder(
            String sessionId,
            AgentDirectorySnapshotDTO snapshot,
            Agent agent,
            ManagedAgentSession managedSession,
            boolean thinking) {
        this.sessionId = sessionId;
        this.snapshot = snapshot;
        this.agent = agent;
        this.managedSession = managedSession;
        this.thinking = thinking;
    }

    public String sessionId() {
        return sessionId;
    }

    public AgentDirectorySnapshotDTO snapshot() {
        return snapshot;
    }

    public Agent agent() {
        return agent;
    }

    public CompletableFuture<Void> prompt(Message message) {
        return managedSession == null ? agent.prompt(message) : managedSession.prompt(message);
    }

    public CompletableFuture<Void> continueQueuedExecution() {
        return managedSession == null ? agent.continueQueuedExecution() : managedSession.continueQueuedExecution();
    }

    public Runnable subscribeCompaction(Consumer<SessionCompactionEvent> listener) {
        return managedSession == null ? () -> {} : managedSession.subscribeCompaction(listener);
    }

    public boolean thinking() {
        return thinking;
    }

    public boolean begin(RuntimeActiveExecution execution) {
        return activeExecution.compareAndSet(null, execution);
    }

    public boolean complete(RuntimeActiveExecution execution) {
        return activeExecution.compareAndSet(execution, null);
    }

    public Optional<RuntimeActiveExecution> activeExecution() {
        return Optional.ofNullable(activeExecution.get());
    }

    public void abort() {
        if (managedSession == null) {
            agent.abort();
            return;
        }
        managedSession.abort();
    }

    public void closeSession() {
        if (managedSession != null) {
            managedSession.close();
        }
    }
}
