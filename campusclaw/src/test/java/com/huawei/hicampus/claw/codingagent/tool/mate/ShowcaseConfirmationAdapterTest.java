/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.AGENT_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.QUERY_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.SKILL_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.binding;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.runtime;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.skill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.tool.AgentContext;
import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.PreparedToolInvocation;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventOutput;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.huawei.hicampus.claw.codingagent.tool.builtin.ToolEntryPoint;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.ShowcaseConfirmationAdapter;
import com.huawei.hicampus.claw.codingagent.tool.question.UserQuestionException;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 验证展示确认通过可信准备快照在两种业务工具入口本地完成。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
class ShowcaseConfirmationAdapterTest {
    private static final String CONFIRM_NAME = ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME;

    @ParameterizedTest
    @EnumSource(Entry.class)
    void bothEntrancesShouldReturnLocalContentWithoutRemoteExecution(Entry entry) {
        AtomicInteger waits = new AtomicInteger();
        Fixture fixture = fixture(entry, "allow", (callId, signal) -> {
            waits.incrementAndGet();
            return answer("真实回答");
        });

        var result = fixture.execute("call-1", validArgs());

        assertThat(result.isError()).isFalse();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("{\"content\":\"真实回答\"}");
        assertThat(waits).hasValue(1);
        assertThat(fixture.client.executeCalls()).isZero();
        assertThat(fixture.beforeCalls).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void bothEntrancesShouldValidateArgumentsBeforeQuestionProjection(Entry entry) {
        AtomicInteger waits = new AtomicInteger();
        Fixture fixture = fixture(entry, "ask", (callId, signal) -> {
            waits.incrementAndGet();
            return answer("不应等待");
        });

        var result = fixture.execute("call-invalid", Map.of("label", "确认", "context", "bad"));

        assertThat(result.isError()).isTrue();
        assertThat(result.details())
                .isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.TOOL_ARGUMENTS_INVALID_ERROR_CODE));
        assertThat(fixture.beforeCalls).hasValue(0);
        assertThat(waits).hasValue(0);
        assertThat(fixture.client.executeCalls()).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = RuntimeToolPermissionPolicy.Decision.class,
            names = {"ASK", "DENY"})
    void nonAllowPermissionShouldRejectTrustedTargetBeforeWaiting(RuntimeToolPermissionPolicy.Decision decision) {
        AtomicInteger waits = new AtomicInteger();
        RuntimeToolPermissionPolicy permissions = mock(RuntimeToolPermissionPolicy.class);
        when(permissions.decide(any(BeforeToolCallContext.class))).thenReturn(decision);
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> {
            waits.incrementAndGet();
            return answer("不应等待");
        });
        adapter.bindPermissions(permissions, QUERY_ID);
        BeforeToolCallContext context = context(invocation("call-denied", CONFIRM_NAME, QUERY_ID, validArgs()));

        String expected = decision == RuntimeToolPermissionPolicy.Decision.ASK
                ? ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_REQUIRED_ERROR_CODE
                : ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_DENIED_ERROR_CODE;
        assertQuestionCode(() -> adapter.prepare(context), expected);
        assertThat(waits).hasValue(0);
    }

    @Test
    void missingPortAndUnboundAdapterShouldUseDedicatedCodes() {
        BeforeToolCallContext context = context(invocation("call-1", CONFIRM_NAME, QUERY_ID, validArgs()));
        RuntimeToolPermissionPolicy permissions = mock(RuntimeToolPermissionPolicy.class);
        when(permissions.decide(any(BeforeToolCallContext.class)))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        var missingPort = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, null);
        missingPort.bindPermissions(permissions, QUERY_ID);
        var unbound = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> null);

        assertQuestionCode(
                () -> missingPort.prepare(context), ClawConstants.RuntimeApi.USER_QUESTION_PORT_MISSING_ERROR_CODE);
        assertQuestionCode(
                () -> unbound.prepare(context), ClawConstants.RuntimeApi.USER_QUESTION_ADAPTER_UNBOUND_ERROR_CODE);
    }

    @Test
    void preparedSnapshotShouldRejectChangedTrustedIdentityAndClearAfterCancellation() throws Exception {
        RuntimeToolPermissionPolicy permissions = mock(RuntimeToolPermissionPolicy.class);
        when(permissions.decide(any(BeforeToolCallContext.class)))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, (callId, signal) -> null);
        adapter.bindPermissions(permissions, QUERY_ID);
        PreparedToolInvocation first = invocation("call-1", CONFIRM_NAME, QUERY_ID, validArgs());
        adapter.prepare(context(first));
        PreparedToolInvocation changed = invocation("call-1", CONFIRM_NAME, "tool-other", validArgs());

        assertQuestionCode(
                () -> adapter.prepared(context(changed)),
                ClawConstants.RuntimeApi.USER_QUESTION_CALL_MISMATCH_ERROR_CODE);
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        assertThatThrownBy(() -> adapter.await(first, cancelled)).isInstanceOf(CancellationException.class);
        assertQuestionCode(
                () -> adapter.prepared(context(first)), ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE);
    }

    @Test
    void invalidAnswerShouldUseDedicatedCodeAndReleasePreparedSnapshot() throws Exception {
        RuntimeToolPermissionPolicy permissions = mock(RuntimeToolPermissionPolicy.class);
        when(permissions.decide(any(BeforeToolCallContext.class)))
                .thenReturn(RuntimeToolPermissionPolicy.Decision.ALLOW);
        var adapter = new ShowcaseConfirmationAdapter(
                true,
                ToolEntryPoint.RUNTIME,
                (callId, signal) ->
                        new QuestionAnswerResultDTO(List.of(new QuestionAnswerDTO("wrong", List.of(), "answer"))));
        adapter.bindPermissions(permissions, QUERY_ID);
        PreparedToolInvocation prepared = invocation("call-answer", CONFIRM_NAME, QUERY_ID, validArgs());
        adapter.prepare(context(prepared));

        assertQuestionCode(
                () -> adapter.await(prepared, new CancellationToken()),
                ClawConstants.RuntimeApi.USER_QUESTION_ANSWER_INVALID_ERROR_CODE);
        assertQuestionCode(
                () -> adapter.prepared(context(prepared)),
                ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE);
    }

    private static Fixture fixture(
            Entry entry, String permission, com.huawei.hicampus.claw.codingagent.tool.question.UserQuestionPort questions) {
        MockMateToolClient client = new MockMateToolClient();
        client.addTool(new MateToolMeta(
                QUERY_ID, CONFIRM_NAME, "Confirm with user", confirmationSchema(), Map.of(), false, "allow"));
        client.bindAgent(AGENT_ID, List.of(QUERY_ID));
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
        PreparedAgentRuntime runtime = runtime(
                List.of(binding(QUERY_ID, CONFIRM_NAME, "用户确认", permission)),
                List.of(skill(List.of(binding(QUERY_ID, CONFIRM_NAME, "用户确认", permission)))));
        MateToolSessionState state = new MateToolsetFactory(client).createSession(runtime, MateCredentials.empty());
        RuntimeToolPermissionPolicy permissions = RuntimeToolPermissionPolicy.from(runtime);
        var adapter = new ShowcaseConfirmationAdapter(true, ToolEntryPoint.RUNTIME, questions);
        adapter.bindPermissions(permissions, RuntimeToolPermissionPolicy.diagnoseBinding(runtime, CONFIRM_NAME));
        state.bindShowcaseConfirmation(adapter);
        RuntimeActiveExecution execution = new RuntimeActiveExecution(RuntimeEventOutput.persistenceOnly());
        execution.bindToolPermissions(permissions);
        execution.installShowcaseConfirmation(adapter);
        AtomicInteger beforeCalls = new AtomicInteger();
        execution.installConfirmationHandler(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        pipeline.setBeforeToolCall(execution::beforeToolCall);
        AgentTool tool =
                entry == Entry.AGENT_DIRECT ? state.createAgentToolAdapters().getFirst() : state.createCallTool();
        return new Fixture(entry, client, pipeline, tool, beforeCalls);
    }

    private static QuestionAnswerResultDTO answer(String content) {
        return new QuestionAnswerResultDTO(List.of(
                new QuestionAnswerDTO(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_ANSWER_ID, List.of(), content)));
    }

    private static BeforeToolCallContext context(PreparedToolInvocation invocation) {
        ToolCall call = new ToolCall(invocation.toolCallId(), invocation.toolName(), invocation.arguments());
        return new BeforeToolCallContext(null, call, invocation.arguments(), null, invocation);
    }

    private static PreparedToolInvocation invocation(
            String callId, String toolName, String targetId, Map<String, Object> arguments) {
        return new PreparedToolInvocation() {
            @Override
            public String toolCallId() {
                return callId;
            }

            @Override
            public String toolName() {
                return toolName;
            }

            @Override
            public Map<String, Object> arguments() {
                return arguments;
            }

            @Override
            public String authorizationTargetName() {
                return CONFIRM_NAME;
            }

            @Override
            public String authorizationTargetId() {
                return targetId;
            }

            @Override
            public AgentToolResult execute(CancellationToken signal, AgentToolUpdateCallback onUpdate) {
                throw new AssertionError("test invocation must not execute remotely");
            }
        };
    }

    private static Map<String, Object> validArgs() {
        return Map.of("label", "确认", "context", Map.of("设备", "空调机组"));
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

    private static void assertQuestionCode(CheckedAction action, String expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(UserQuestionException.class, error -> assertThat(error.stableErrorCode())
                        .isEqualTo(expected));
    }

    private enum Entry {
        AGENT_DIRECT,
        SKILL_CALL_TOOL
    }

    private record Fixture(
            Entry entry,
            MockMateToolClient client,
            ToolExecutionPipeline pipeline,
            AgentTool tool,
            AtomicInteger beforeCalls) {
        private com.huawei.hicampus.claw.ai.types.ToolResultMessage execute(String callId, Map<String, Object> businessArgs) {
            Map<String, Object> arguments =
                    entry == Entry.AGENT_DIRECT ? businessArgs : Map.of("tool", CONFIRM_NAME, "args", businessArgs);
            ToolCall call = new ToolCall(callId, tool.name(), arguments);
            return pipeline.execute(tool, call, arguments, new AgentContext(), new CancellationToken(), null);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
