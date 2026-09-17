/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolArgumentsInvalidException;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.ShowcaseConfirmationAdapter;
import com.huawei.hicampus.claw.codingagent.tool.question.UserQuestionException;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 验证展示确认适配器的原始载荷、本地等待、入口和取消边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
class ShowcaseConfirmationAdapterTest {
    private static final String TOOL_ID = "tool-11111111111111111111111111111111";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMateToolClient client;

    private MateToolSessionState state;

    private RuntimeToolPermissionPolicy permissions;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(new MateToolMeta(
                TOOL_ID,
                ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                "description",
                Map.of(),
                Map.of(),
                false,
                "allow"));
        client.bindAgent("agent-1", List.of(TOOL_ID));
        state = new MateToolsetFactory(client)
                .createSession("agent-1", Map.of(), MateCredentials.jwt("caller", "token", "access"));
        permissions = mock(RuntimeToolPermissionPolicy.class);
    }

    @Test
    void preparedCallShouldKeepPayloadAndReturnLocalContentWithoutRemoteExecution() throws Exception {
        var expected = new QuestionAnswerResultDTO(List.of(
                new QuestionAnswerDTO(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_ANSWER_ID, List.of(), "真实回答")));
        var adapter = runtimeAdapter((callId, signal) -> expected);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-1", "  ", Map.of("嵌套", List.of(1, nullValue(), Map.of("值", "保留"))));

        var snapshot = adapter.prepare(call);
        var leaked = snapshot.context();
        leaked.withObject("new").put("value", 1);
        var result = tool.execute(call.id(), call.arguments(), new CancellationToken(), null);

        assertThat(snapshot.label()).isEqualTo("  ");
        assertThat(snapshot.context())
                .isEqualTo(MAPPER.valueToTree(call.arguments().get("args")).get("context"));
        assertThat(snapshot.context().has("new")).isFalse();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("{\"content\":\"真实回答\"}");
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void invalidContextShouldFailWithoutStartingAQuestionOrRemoteExecution() {
        var adapter = runtimeAdapter((callId, signal) -> null);
        state.createCallTool(adapter);
        ToolCall call = new ToolCall(
                "call-invalid",
                "CallMateTool",
                Map.of(
                        "tool",
                        ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                        "args",
                        Map.of("label", "x", "context", "bad")));

        assertThatThrownBy(() -> adapter.prepare(call)).isInstanceOf(ToolArgumentsInvalidException.class);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void cancellationShouldClearPreparedCallForTheNextQuestion() throws Exception {
        var adapter = runtimeAdapter((callId, signal) -> null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall first = call("call-1", "first", Map.of());
        adapter.prepare(first);
        var cancelled = new CancellationToken();
        cancelled.cancel();

        assertThatThrownBy(() -> tool.execute(first.id(), first.arguments(), cancelled, null))
                .isInstanceOf(CancellationException.class);
        ToolCall second = call("call-2", "second", Map.of());
        assertThat(adapter.prepare(second).label()).isEqualTo("second");
    }

    @Test
    void childEntryShouldRejectTargetBeforeRemoteExecution() {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.CHILD_AGENT, null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-child", "question", Map.of());

        assertThatThrownBy(() -> tool.execute(call.id(), call.arguments(), new CancellationToken(), null))
                .isInstanceOf(UserQuestionException.class)
                .hasMessage(ClawConstants.RuntimeApi.USER_QUESTION_UNAVAILABLE_ERROR_CODE);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void cronEntryShouldRejectTargetBeforeRemoteExecution() {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.CRON, null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-cron", "question", Map.of());

        assertThatThrownBy(() -> tool.execute(call.id(), call.arguments(), new CancellationToken(), null))
                .isInstanceOf(UserQuestionException.class);
        assertThat(client.executeCalls()).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = RuntimeToolPermissionPolicy.Decision.class,
            names = {"ASK", "DENY"})
    void nonAllowPermissionShouldRejectWithoutWaitingOrRemoteExecution(RuntimeToolPermissionPolicy.Decision decision) {
        AtomicInteger waits = new AtomicInteger();
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> {
            waits.incrementAndGet();
            return null;
        });
        adapter.bindPermissions(permissions, TOOL_ID);
        when(permissions.decide(org.mockito.ArgumentMatchers.any())).thenReturn(decision);
        state.createCallTool(adapter);

        assertThatThrownBy(() -> adapter.prepare(call("call-denied", "question", Map.of())))
                .isInstanceOf(UserQuestionException.class);
        assertThat(waits).hasValue(0);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void missingOrAmbiguousBindingShouldRejectWithoutWaitingOrRemoteExecution() {
        var missingClient = new MockMateToolClient();
        missingClient.bindAgent("agent-1", List.of());
        MateToolSessionState missingState = new MateToolsetFactory(missingClient)
                .createSession("agent-1", Map.of(), MateCredentials.jwt("caller", "token", "access"));
        var missing = runtimeAdapter((callId, signal) -> null);
        missingState.createCallTool(missing);
        assertThatThrownBy(() -> missing.prepare(call("call-missing", "question", Map.of())))
                .isInstanceOf(UserQuestionException.class);

        String otherId = "tool-22222222222222222222222222222222";
        client.addTool(new MateToolMeta(
                otherId,
                ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                "other",
                Map.of(),
                Map.of(),
                false,
                "allow"));
        client.bindSkill("skill-1", List.of(otherId));
        MateToolSessionState ambiguousState = new MateToolsetFactory(client)
                .createSession("agent-1", Map.of("skill", "skill-1"), MateCredentials.jwt("caller", "token", "access"));
        var ambiguous = runtimeAdapter((callId, signal) -> null);
        ambiguousState.createCallTool(ambiguous);
        assertThatThrownBy(() -> ambiguous.prepare(call("call-ambiguous", "question", Map.of())))
                .isInstanceOf(UserQuestionException.class);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void discoveredIdentityMismatchShouldRejectBeforeWaitingOrRemoteExecution() {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> null);
        adapter.bindPermissions(permissions, "tool-33333333333333333333333333333333");
        when(permissions.decide(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        state.createCallTool(adapter);

        assertThatThrownBy(() -> adapter.prepare(call("call-mismatch", "question", Map.of())))
                .isInstanceOf(UserQuestionException.class);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void disabledShowcaseShouldKeepRemoteToolExecution() throws Exception {
        var adapter = new ShowcaseConfirmationAdapter(false, ToolEntryPoint.RUNTIME, null);
        CallMateTool tool = state.createCallTool(adapter);
        ToolCall call = call("call-remote", "question", Map.of());

        tool.execute(call.id(), call.arguments(), new CancellationToken(), null);

        assertThat(client.executeCalls()).isEqualTo(1);
        assertThat(client.lastCalledTool()).isEqualTo(TOOL_ID);
    }

    private ShowcaseConfirmationAdapter runtimeAdapter(
            com.huawei.hicampus.claw.codingagent.tool.question.UserQuestionPort questions) {
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, questions);
        adapter.bindPermissions(permissions, TOOL_ID);
        when(permissions.decide(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        return adapter;
    }

    private static ToolCall call(String id, String label, Map<String, Object> context) {
        return new ToolCall(
                id,
                "CallMateTool",
                Map.of(
                        "tool",
                        ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME,
                        "args",
                        Map.of("label", label, "context", context, "extra", true)));
    }

    private static Object nullValue() {
        return MAPPER.nullNode();
    }
}
