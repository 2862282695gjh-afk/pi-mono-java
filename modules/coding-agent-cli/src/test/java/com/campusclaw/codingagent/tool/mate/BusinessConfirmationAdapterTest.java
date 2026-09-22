/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.AGENT_ID;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.QUERY_ID;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.SKILL_ID;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.binding;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.runtime;
import static com.campusclaw.codingagent.tool.mate.MateToolTestSupport.skill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.BeforeToolCallResult;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.question.PreparedQuestionInvocation;
import com.campusclaw.codingagent.tool.question.QuestionAnswerDTO;
import com.campusclaw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.campusclaw.codingagent.tool.question.QuestionRequestSnapshotDTO;
import com.campusclaw.codingagent.tool.question.UserQuestionException;
import com.campusclaw.common.constant.ClawConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 验证业务确认在可信校验后固定本地问题调用且不回退远端。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/23]
 * @since [br_eCampusCore 26.0.0]
 */
class BusinessConfirmationAdapterTest {
    private static final String CONFIRM_NAME = ClawConstants.RuntimeApi.BUSINESS_CONFIRMATION_TOOL_NAME;

    @ParameterizedTest
    @EnumSource(Entry.class)
    void bothEntrancesShouldExposeSnapshotAndReturnLocalContent(Entry entry) {
        AtomicInteger waits = new AtomicInteger();
        Fixture fixture = fixture(entry, ToolEntryPoint.RUNTIME, (callId, signal) -> {
            waits.incrementAndGet();
            return answer("真实回答");
        });
        AtomicReference<QuestionRequestSnapshotDTO> snapshot = captureQuestion(fixture.pipeline());

        var result = fixture.execute("call-1", validArgs());

        assertThat(result.isError()).isFalse();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("{\"content\":\"真实回答\"}");
        assertThat(snapshot.get().isBusinessConfirmation()).isTrue();
        assertThat(snapshot.get().label()).isEqualTo("  请确认  ");
        assertThat(snapshot.get().context().path("device").asText()).isEqualTo("AHU-01");
        assertThat(waits).hasValue(1);
        assertThat(fixture.client().executeCalls()).isZero();
    }

    @Test
    void validatedInvocationShouldFreezeNestedNullArgumentsAndRawContext() {
        Fixture fixture = fixture(Entry.AGENT_DIRECT, ToolEntryPoint.RUNTIME, (callId, signal) -> answer("ok"));
        AtomicReference<PreparedQuestionInvocation> prepared = new AtomicReference<>();
        fixture.pipeline().setBeforeToolCall(context -> {
            prepared.set((PreparedQuestionInvocation) context.preparedInvocation());
            return BeforeToolCallResult.allow();
        });
        List<Object> values = new ArrayList<>();
        values.add(null);
        values.add("fixed");
        Map<String, Object> context = new HashMap<>();
        context.put("values", values);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("label", " raw ");
        arguments.put("context", context);

        var result = fixture.execute("call-snapshot", arguments);
        values.set(1, "changed");
        context.put("late", true);

        assertThat(result.isError()).isFalse();
        assertThat(prepared.get().questionRequest().label()).isEqualTo(" raw ");
        assertThat(prepared.get().questionRequest().context().toString()).isEqualTo("{\"values\":[null,\"fixed\"]}");
        assertThat(prepared.get().arguments())
                .isEqualTo(
                        Map.of("label", " raw ", "context", Map.of("values", java.util.Arrays.asList(null, "fixed"))));
    }

    @ParameterizedTest
    @EnumSource(
            value = ToolEntryPoint.class,
            names = {"CRON", "CHILD_AGENT"})
    void nonRuntimeEntrancesShouldRefuseLocallyWithoutFallback(ToolEntryPoint entryPoint) {
        Fixture fixture = fixture(Entry.SKILL_CALL_TOOL, entryPoint, (callId, signal) -> answer("never"));

        var result = fixture.execute("call-blocked", validArgs());

        assertThat(result.isError()).isTrue();
        assertThat(result.details())
                .isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.USER_QUESTION_ENTRY_UNSUPPORTED_ERROR_CODE));
        assertThat(fixture.client().executeCalls()).isZero();
    }

    @Test
    void missingPortAndUnboundFactoryShouldRefuseLocallyWithoutFallback() {
        Fixture missing = fixture(Entry.AGENT_DIRECT, ToolEntryPoint.RUNTIME, null);
        MateToolSessionState unbound = state(missing.client());

        var missingResult = missing.execute("call-missing", validArgs());
        var unboundResult = execute(
                unbound.createCallTool(), "call-unbound", wrapForCallTool(validArgs()), new ToolExecutionPipeline());

        assertThat(missingResult.details())
                .isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.USER_QUESTION_PORT_MISSING_ERROR_CODE));
        assertThat(unboundResult.details())
                .isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.USER_QUESTION_PORT_MISSING_ERROR_CODE));
        assertThat(missing.client().executeCalls()).isZero();
    }

    @Test
    void standaloneDirectInvokeShouldRefuseUnsupportedEntryWithoutRemoteFallback() {
        Fixture fixture = fixture(Entry.AGENT_DIRECT, ToolEntryPoint.CHILD_AGENT, (callId, signal) -> answer("never"));
        AgentTool tool = fixture.tool();

        assertThatThrownBy(() -> tool.execute("call-direct", validArgs(), new CancellationToken(), partial -> {}))
                .isInstanceOfSatisfying(UserQuestionException.class, error -> assertThat(error.stableErrorCode())
                        .isEqualTo(ClawConstants.RuntimeApi.USER_QUESTION_ENTRY_UNSUPPORTED_ERROR_CODE));
        assertThat(fixture.client().executeCalls()).isZero();
    }

    @Test
    void businessSchemaFailureShouldKeepRecoveryAndSkipQuestion() {
        AtomicInteger waits = new AtomicInteger();
        Fixture fixture = fixture(Entry.SKILL_CALL_TOOL, ToolEntryPoint.RUNTIME, (callId, signal) -> {
            waits.incrementAndGet();
            return answer("never");
        });

        var result = fixture.execute("call-invalid", Map.of("label", "确认", "context", "bad"));

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .contains("\"definitionComplete\":true", "\"tool\":\"confirm_with_user\"");
        assertThat(result.details())
                .isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.TOOL_ARGUMENTS_INVALID_ERROR_CODE));
        assertThat(waits).hasValue(0);
        assertThat(fixture.client().executeCalls()).isZero();
    }

    @Test
    void duplicateSameCallIdShouldCreateIndependentQuestionInvocations() {
        AtomicInteger waits = new AtomicInteger();
        Fixture fixture = fixture(Entry.AGENT_DIRECT, ToolEntryPoint.RUNTIME, (callId, signal) -> {
            waits.incrementAndGet();
            return answer("ok");
        });

        var first = fixture.execute("same-call", validArgs());
        var second = fixture.execute("same-call", validArgs());

        assertThat(first.isError()).isFalse();
        assertThat(second.isError()).isFalse();
        assertThat(waits).hasValue(2);
        assertThat(fixture.client().executeCalls()).isZero();
    }

    @Test
    void invalidAnswerShouldReturnStableErrorWithoutFallback() {
        Fixture fixture = fixture(
                Entry.AGENT_DIRECT,
                ToolEntryPoint.RUNTIME,
                (callId, signal) ->
                        new QuestionAnswerResultDTO(List.of(new QuestionAnswerDTO("wrong", List.of(), "answer"))));

        var result = fixture.execute("call-answer", validArgs());

        assertThat(result.isError()).isTrue();
        assertThat(result.details())
                .isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.USER_QUESTION_ANSWER_INVALID_ERROR_CODE));
        assertThat(fixture.client().executeCalls()).isZero();
    }

    private static AtomicReference<QuestionRequestSnapshotDTO> captureQuestion(ToolExecutionPipeline pipeline) {
        AtomicReference<QuestionRequestSnapshotDTO> captured = new AtomicReference<>();
        pipeline.setBeforeToolCall(context -> {
            PreparedQuestionInvocation invocation = (PreparedQuestionInvocation) context.preparedInvocation();
            captured.set(invocation.questionRequest());
            return BeforeToolCallResult.allow();
        });
        return captured;
    }

    private static Fixture fixture(
            Entry entry,
            ToolEntryPoint entryPoint,
            com.campusclaw.codingagent.tool.question.UserQuestionPort questions) {
        MockMateToolClient client = confirmationClient();
        MateToolSessionState state = state(client);
        state.bindBusinessConfirmation(entryPoint, questions);
        AgentTool tool =
                entry == Entry.AGENT_DIRECT ? state.createAgentToolAdapters().getFirst() : state.createCallTool();
        return new Fixture(entry, client, new ToolExecutionPipeline(), tool);
    }

    private static MateToolSessionState state(MockMateToolClient client) {
        PreparedAgentRuntime prepared = runtime(
                List.of(binding(QUERY_ID, CONFIRM_NAME, "用户确认", "allow")),
                List.of(skill(List.of(binding(QUERY_ID, CONFIRM_NAME, "用户确认", "allow")))));
        return new MateToolsetFactory(client).createSession(prepared, MateCredentials.empty());
    }

    private static MockMateToolClient confirmationClient() {
        MockMateToolClient client = new MockMateToolClient();
        client.addTool(new MateToolMeta(
                QUERY_ID, CONFIRM_NAME, "Confirm with user", confirmationSchema(), Map.of(), false, "allow"));
        client.bindAgent(AGENT_ID, List.of(QUERY_ID));
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
        return client;
    }

    private static QuestionAnswerResultDTO answer(String content) {
        return new QuestionAnswerResultDTO(List.of(
                new QuestionAnswerDTO(ClawConstants.RuntimeApi.BUSINESS_CONFIRMATION_ANSWER_ID, List.of(), content)));
    }

    private static Map<String, Object> validArgs() {
        return Map.of("label", "  请确认  ", "context", Map.of("device", "AHU-01"));
    }

    private static Map<String, Object> wrapForCallTool(Map<String, Object> businessArgs) {
        return Map.of("tool", CONFIRM_NAME, "args", businessArgs);
    }

    private static Map<String, Object> confirmationSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "label", Map.of("type", "string"),
                        "context", Map.of("type", "object")),
                "required",
                List.of("label"),
                "additionalProperties",
                false);
    }

    private static com.campusclaw.ai.types.ToolResultMessage execute(
            AgentTool tool, String callId, Map<String, Object> arguments, ToolExecutionPipeline pipeline) {
        ToolCall call = new ToolCall(callId, tool.name(), arguments);
        return pipeline.execute(tool, call, arguments, new AgentContext(), new CancellationToken(), null);
    }

    private enum Entry {
        AGENT_DIRECT,
        SKILL_CALL_TOOL
    }

    private record Fixture(Entry entry, MockMateToolClient client, ToolExecutionPipeline pipeline, AgentTool tool) {
        private com.campusclaw.ai.types.ToolResultMessage execute(String callId, Map<String, Object> businessArgs) {
            Map<String, Object> arguments = entry == Entry.AGENT_DIRECT ? businessArgs : wrapForCallTool(businessArgs);
            return BusinessConfirmationAdapterTest.execute(tool, callId, arguments, pipeline);
        }
    }
}
