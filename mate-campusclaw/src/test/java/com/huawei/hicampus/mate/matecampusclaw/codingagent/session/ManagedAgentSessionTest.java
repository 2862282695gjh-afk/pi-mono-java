/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.huawei.hicampus.mate.matecampusclaw.agent.Agent;
import com.huawei.hicampus.mate.matecampusclaw.agent.state.AgentState;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Api;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.InputModality;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ModelCost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingLevel;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.CompactionReason;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionStartedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactor;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.Test;

/**
 * 验证公共 Session 的压缩、回滚和溢出重试语义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class ManagedAgentSessionTest {
    @Test
    void appliesThresholdCompactionAndPublishesLifecycle() {
        Fixture fixture = fixture();
        SessionCompactionResult result = result(fixture.messages());
        when(fixture.compactor().exceedsThreshold(fixture.messages(), fixture.model()))
                .thenReturn(true);
        when(fixture.compactor()
                        .compact(eq(fixture.messages()), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq(null)))
                .thenReturn(CompletableFuture.completedFuture(result));
        List<SessionCompactionEvent> events = new ArrayList<>();
        fixture.session().subscribeCompaction(events::add);

        fixture.session().prompt("continue").join();

        assertThat(events)
                .extracting(Object::getClass)
                .containsExactly(SessionCompactionStartedEvent.class, SessionCompactionCompletedEvent.class);
        verify(fixture.agent()).replaceMessages(any());
        verify(fixture.agent(), never()).continueExecution();
    }

    @Test
    void retriesOverflowOnlyOnceAfterSuccessfulCompaction() {
        Fixture fixture = fixture();
        List<Message> compactable = List.of(fixture.messages().getFirst());
        when(fixture.compactor().isOverflow(fixture.messages(), fixture.model()))
                .thenReturn(true);
        when(fixture.compactor().compact(eq(compactable), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq(null)))
                .thenReturn(CompletableFuture.completedFuture(result(compactable)));
        when(fixture.agent().continueExecution()).thenReturn(CompletableFuture.completedFuture(null));

        fixture.session().prompt("continue").join();

        verify(fixture.agent(), times(1)).continueExecution();
        verify(fixture.compactor(), times(1)).isOverflow(fixture.messages(), fixture.model());
    }

    @Test
    void preservesMessagesWhenOverflowCompactionFails() {
        Fixture fixture = fixture();
        List<Message> compactable = List.of(fixture.messages().getFirst());
        CompletableFuture<SessionCompactionResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("summary unavailable"));
        when(fixture.compactor().isOverflow(fixture.messages(), fixture.model()))
                .thenReturn(true);
        when(fixture.compactor().compact(eq(compactable), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq(null)))
                .thenReturn(failed);
        List<SessionCompactionEvent> events = new ArrayList<>();
        fixture.session().subscribeCompaction(events::add);

        fixture.session().prompt("continue").join();

        assertThat(events)
                .extracting(Object::getClass)
                .containsExactly(SessionCompactionStartedEvent.class, SessionCompactionFailedEvent.class);
        assertThat(((SessionCompactionStartedEvent) events.getFirst()).reason()).isEqualTo(CompactionReason.OVERFLOW);
        verify(fixture.agent(), never()).replaceMessages(any());
        verify(fixture.agent(), never()).continueExecution();
    }

    @Test
    void publishesFailureWhenCompactorRejectsSynchronously() {
        Fixture fixture = fixture();
        when(fixture.compactor().exceedsThreshold(fixture.messages(), fixture.model()))
                .thenReturn(true);
        when(fixture.compactor()
                        .compact(eq(fixture.messages()), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq(null)))
                .thenThrow(new IllegalStateException("nothing to compact"));
        List<SessionCompactionEvent> events = new ArrayList<>();
        fixture.session().subscribeCompaction(events::add);

        fixture.session().prompt("continue").join();

        assertThat(events)
                .extracting(Object::getClass)
                .containsExactly(SessionCompactionStartedEvent.class, SessionCompactionFailedEvent.class);
        verify(fixture.agent(), never()).replaceMessages(any());
    }

    @Test
    void abortCancelsActiveCompactionAndAgentExecution() {
        Fixture fixture = fixture();
        CompletableFuture<SessionCompactionResult> compaction = new CompletableFuture<>();
        when(fixture.compactor()
                        .compact(eq(fixture.messages()), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq(null)))
                .thenReturn(compaction);

        fixture.session().compact(null);
        fixture.session().abort();

        assertThat(compaction).isCancelled();
        verify(fixture.agent()).abort();
        verify(fixture.agent()).clearSteeringQueue();
        verify(fixture.agent()).clearFollowUpQueue();
    }

    private static Fixture fixture() {
        Model model = model();
        List<Message> messages = messages(model);
        AgentState state = new AgentState();
        state.setModel(model);
        state.setThinkingLevel(ThinkingLevel.MEDIUM);
        state.setMessages(messages);
        Agent agent = mock(Agent.class);
        when(agent.getState()).thenReturn(state);
        when(agent.prompt(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));
        SessionCompactor compactor = mock(SessionCompactor.class);
        ManagedAgentSession session = new ManagedAgentSession(
                mock(PreparedAgentRuntime.class), ToolEntryPoint.RUNTIME, agent, List.of(), compactor);
        return new Fixture(session, agent, compactor, model, messages);
    }

    private static SessionCompactionResult result(List<Message> retained) {
        return new SessionCompactionResult("summary", retained, 1, 100, 20, Usage.empty());
    }

    private static List<Message> messages(Model model) {
        return List.of(
                new UserMessage("earlier context", 1L),
                new AssistantMessage(
                        List.of(new TextContent("response")),
                        model.api().value(),
                        model.provider().value(),
                        model.id(),
                        null,
                        Usage.empty(),
                        StopReason.STOP,
                        null,
                        2L));
    }

    private static Model model() {
        return new Model(
                "model-a",
                "Model A",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(0, 0, 0, 0),
                1000,
                100,
                null,
                null,
                null);
    }

    private record Fixture(
            ManagedAgentSession session,
            Agent agent,
            SessionCompactor compactor,
            Model model,
            List<Message> messages) {}
}
