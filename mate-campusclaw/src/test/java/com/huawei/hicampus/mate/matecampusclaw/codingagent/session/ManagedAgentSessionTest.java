/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.AutomaticCompactionDecision;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.AutomaticCompactionDecision.Action;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionCompletedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionFailedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionResult;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactionStartedEvent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactor;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction.SessionCompactor.PreparedCompaction;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin.ToolEntryPoint;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 验证公共 Session 的压缩、回滚和一次溢出恢复状态机。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class ManagedAgentSessionTest {
    @Test
    void appliesThresholdCompactionAndPublishesLifecycle() {
        Fixture fixture = fixture();
        stubDecisions(fixture, none(fixture), decision(Action.THRESHOLD, fixture));
        stubSuccessfulCompaction(fixture, fixture.messages());
        List<SessionCompactionEvent> events = subscribe(fixture);

        fixture.session().prompt("continue").join();

        assertThat(events)
                .extracting(Object::getClass)
                .containsExactly(SessionCompactionStartedEvent.class, SessionCompactionCompletedEvent.class);
        verify(fixture.agent()).replaceMessages(any());
        verify(fixture.agent(), never()).continueExecution();
    }

    @Test
    void preservesSuccessfulStopOverflowWithoutRetry() {
        Fixture fixture = fixture();
        stubDecisions(fixture, none(fixture), decision(Action.OVERFLOW_PRESERVE, fixture));
        stubSuccessfulCompaction(fixture, fixture.messages());

        fixture.session().prompt("continue").join();

        verify(fixture.compactor()).prepare(fixture.messages());
        verify(fixture.agent(), never()).continueExecution();
    }

    @Test
    void retriesErrorOverflowOnceAfterSuccessfulCompaction() {
        Fixture fixture = fixture();
        List<Message> retryMessages =
                fixture.messages().subList(0, fixture.messages().size() - 1);
        stubDecisions(fixture, none(fixture), decision(Action.OVERFLOW_RETRY, fixture), none(fixture));
        stubSuccessfulCompaction(fixture, retryMessages);

        fixture.session().prompt("continue").join();

        verify(fixture.compactor()).prepare(retryMessages);
        verify(fixture.agent(), times(1)).continueExecution();
    }

    @Test
    void reportsSecondOverflowWithoutASecondCompaction() {
        Fixture fixture = fixture();
        List<Message> retryMessages =
                fixture.messages().subList(0, fixture.messages().size() - 1);
        stubDecisions(
                fixture,
                none(fixture),
                decision(Action.OVERFLOW_RETRY, fixture),
                decision(Action.OVERFLOW_RETRY, fixture));
        stubSuccessfulCompaction(fixture, retryMessages);
        List<SessionCompactionEvent> events = subscribe(fixture);

        fixture.session().prompt("continue").join();

        assertThat(events.getLast()).isInstanceOf(SessionCompactionFailedEvent.class);
        assertThat(((SessionCompactionFailedEvent) events.getLast()).willRetry())
                .isFalse();
        assertThat(((SessionCompactionFailedEvent) events.getLast()).message()).contains("after one");
        verify(fixture.compactor(), times(1)).compact(any(), any(), any(), any());
    }

    @Test
    void compactsRecoveredOverflowBeforeSubmittingNewPrompt() {
        Fixture fixture = fixture();
        List<Message> retryMessages =
                fixture.messages().subList(0, fixture.messages().size() - 1);
        stubDecisions(fixture, decision(Action.OVERFLOW_RETRY, fixture), none(fixture));
        stubSuccessfulCompaction(fixture, retryMessages);

        fixture.session().prompt("new prompt").join();

        InOrder order = inOrder(fixture.compactor(), fixture.agent());
        order.verify(fixture.compactor()).compact(any(), any(), any(), any());
        order.verify(fixture.agent()).replaceMessages(any());
        order.verify(fixture.agent()).prompt(any(Message.class));
        verify(fixture.agent(), never()).continueExecution();
    }

    @Test
    void silentlySkipsAutomaticCompactionWhenNothingCanBePrepared() {
        Fixture fixture = fixture();
        stubDecisions(fixture, none(fixture), decision(Action.THRESHOLD, fixture));
        when(fixture.compactor().prepare(fixture.messages())).thenReturn(null);
        List<SessionCompactionEvent> events = subscribe(fixture);

        fixture.session().prompt("continue").join();

        assertThat(events).isEmpty();
        verify(fixture.compactor(), never()).compact(any(), any(), any(), any());
    }

    @Test
    void preservesMessagesWhenOverflowCompactionFails() {
        Fixture fixture = fixture();
        List<Message> retryMessages =
                fixture.messages().subList(0, fixture.messages().size() - 1);
        stubDecisions(fixture, none(fixture), decision(Action.OVERFLOW_RETRY, fixture));
        PreparedCompaction prepared = prepared(retryMessages);
        when(fixture.compactor().prepare(retryMessages)).thenReturn(prepared);
        CompletableFuture<SessionCompactionResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("summary unavailable"));
        when(fixture.compactor().compact(eq(prepared), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq(null)))
                .thenReturn(failed);
        List<SessionCompactionEvent> events = subscribe(fixture);

        fixture.session().prompt("continue").join();

        assertThat(events)
                .extracting(Object::getClass)
                .containsExactly(SessionCompactionStartedEvent.class, SessionCompactionFailedEvent.class);
        verify(fixture.agent(), never()).replaceMessages(any());
        verify(fixture.agent(), never()).continueExecution();
    }

    @Test
    void manualCompactionAbortsExecutionWaitsAndThenCompacts() {
        Fixture fixture = fixture();
        PreparedCompaction prepared = prepared(fixture.messages());
        when(fixture.compactor().prepare(fixture.messages())).thenReturn(prepared);
        when(fixture.compactor().compact(eq(prepared), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq("focus")))
                .thenReturn(CompletableFuture.completedFuture(result(fixture.messages())));
        when(fixture.compactor().compactedMessages(any())).thenReturn(fixture.messages());

        fixture.session().compact("focus").join();

        InOrder order = inOrder(fixture.agent(), fixture.compactor());
        order.verify(fixture.agent()).abort();
        order.verify(fixture.agent()).waitForIdle();
        order.verify(fixture.compactor()).compact(any(), any(), any(), eq("focus"));
    }

    @Test
    void marksCancelledCompactionAsAborted() {
        Fixture fixture = fixture();
        PreparedCompaction prepared = prepared(fixture.messages());
        CompletableFuture<SessionCompactionResult> pending = new CompletableFuture<>();
        when(fixture.compactor().prepare(fixture.messages())).thenReturn(prepared);
        when(fixture.compactor().compact(eq(prepared), any(), any(), eq(null))).thenReturn(pending);
        List<SessionCompactionEvent> events = subscribe(fixture);

        fixture.session().compact(null);
        fixture.session().abort();

        assertThat(events.getLast()).isInstanceOf(SessionCompactionFailedEvent.class);
        assertThat(((SessionCompactionFailedEvent) events.getLast()).aborted()).isTrue();
        assertThat(pending).isCancelled();
    }

    private static List<SessionCompactionEvent> subscribe(Fixture fixture) {
        List<SessionCompactionEvent> events = new ArrayList<>();
        fixture.session().subscribeCompaction(events::add);
        return events;
    }

    private static void stubDecisions(Fixture fixture, AutomaticCompactionDecision... decisions) {
        when(fixture.compactor().decide(any(), eq(fixture.model()), any(Boolean.class)))
                .thenReturn(decisions[0], java.util.Arrays.copyOfRange(decisions, 1, decisions.length));
    }

    private static void stubSuccessfulCompaction(Fixture fixture, List<Message> compactable) {
        PreparedCompaction prepared = prepared(compactable);
        SessionCompactionResult result = result(compactable);
        when(fixture.compactor().prepare(compactable)).thenReturn(prepared);
        when(fixture.compactor().compact(eq(prepared), eq(fixture.model()), eq(ThinkingLevel.MEDIUM), eq(null)))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(fixture.compactor().compactedMessages(result)).thenReturn(compactable);
    }

    private static AutomaticCompactionDecision none(Fixture fixture) {
        return decision(Action.NONE, fixture);
    }

    private static AutomaticCompactionDecision decision(Action action, Fixture fixture) {
        return new AutomaticCompactionDecision(
                action, (AssistantMessage) fixture.messages().getLast());
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
        when(agent.continueExecution()).thenReturn(CompletableFuture.completedFuture(null));
        when(agent.waitForIdle()).thenReturn(CompletableFuture.completedFuture(null));
        SessionCompactor compactor = mock(SessionCompactor.class);
        ManagedAgentSession session = new ManagedAgentSession(
                mock(PreparedAgentRuntime.class), ToolEntryPoint.RUNTIME, agent, List.of(), compactor);
        return new Fixture(session, agent, compactor, model, messages);
    }

    private static PreparedCompaction prepared(List<Message> retained) {
        return new PreparedCompaction(List.of(), List.of(), retained, 100, null, Set.of(), 1, false);
    }

    private static SessionCompactionResult result(List<Message> retained) {
        return new SessionCompactionResult("summary", retained, 1, 100, 20, Usage.empty());
    }

    private static List<Message> messages(Model model) {
        return List.of(
                new UserMessage("earlier context", 1L),
                new AssistantMessage(
                        List.of(new TextContent("earlier answer")),
                        model.api().value(),
                        model.provider().value(),
                        model.id(),
                        null,
                        Usage.empty(),
                        StopReason.STOP,
                        null,
                        2L),
                new UserMessage("current task", 3L),
                new AssistantMessage(
                        List.of(new TextContent("response")),
                        model.api().value(),
                        model.provider().value(),
                        model.id(),
                        null,
                        Usage.empty(),
                        StopReason.STOP,
                        null,
                        4L));
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
                1_000,
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
