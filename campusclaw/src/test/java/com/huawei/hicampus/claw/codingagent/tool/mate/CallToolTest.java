/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.QUERY_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.SKILL_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.binding;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.meta;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.requiredValueSchema;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.runtime;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.skill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.tool.AfterToolCallResult;
import com.huawei.hicampus.claw.agent.tool.AgentContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CallTool} 的可信 Skill 范围、业务 Schema 和单次执行测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
class CallToolTest {

    private MockMateToolClient client;

    private MateCredentials credentials;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        credentials = MateCredentials.jwt("caller-1", "token-1", "access-token-1");
        client.addTool(meta(QUERY_ID, "Query", requiredValueSchema()));
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
    }

    @Test
    void shouldPublishFixedOuterContractAndDefaultMissingArgsToEmptyObject() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();

        assertThat(tool.name()).isEqualTo("CallTool");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
        assertThat(tool.parameters().path("required").get(0).asText()).isEqualTo("tool");
        assertThat(tool.parameters().path("properties").has("skill")).isFalse();
        assertThatThrownBy(() -> tool.execute("call", Map.of("tool", "Query"), null, null))
                .isInstanceOf(MateToolExecutionException.class)
                .hasMessageContaining("arguments failed validation");
        assertThat(client.lastCallArgs()).isNull();
    }

    @Test
    void shouldExecuteOnlySkillBoundToolWithCredentialsAndCacheMetadata() throws Exception {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();

        tool.execute("call-1", Map.of("tool", "Query", "args", Map.of("value", "one")), null, null);
        tool.execute("call-2", Map.of("tool", "Query", "args", Map.of("value", "two")), null, null);

        assertThat(client.agentListCalls()).isZero();
        assertThat(client.skillListCalls()).isEqualTo(1);
        assertThat(client.executeCalls()).isEqualTo(2);
        assertThat(client.lastCalledTool()).isEqualTo(QUERY_ID);
        assertThat(client.lastCallArgs()).isEqualTo(Map.of("value", "two"));
        assertThat(client.lastCallCredentials()).isSameAs(credentials);
    }

    @Test
    void shouldRejectAgentOnlyUnknownFrameworkAndArbitraryIdTargets() {
        CallTool tool = state(List.of(binding(QUERY_ID, "Query", "查询", "allow")), List.of())
                .createCallTool();

        assertRejected(tool, "Query");
        assertRejected(tool, "Read");
        assertRejected(tool, QUERY_ID);
        assertRejected(tool, "https://example.com/tool");
        assertThat(client.skillListCalls()).isZero();
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void businessSchemaFailureShouldRunExistingBeforeAndAfterLifecycleWithoutHttpCall() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger beforeCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        pipeline.setBeforeToolCall(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        pipeline.setAfterToolCall(context -> {
            afterCalls.incrementAndGet();
            return new AfterToolCallResult(null, null, null);
        });
        Map<String, Object> arguments = Map.of("tool", "Query", "args", Map.of());

        var result = pipeline.execute(
                tool,
                new ToolCall("call", "CallTool", arguments),
                arguments,
                new AgentContext(),
                new CancellationToken(),
                null);

        assertThat(result.isError()).isTrue();
        assertThat(beforeCalls).hasValue(1);
        assertThat(afterCalls).hasValue(1);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void cancellationAndMateFailureShouldNotReplayExecution() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();

        assertThatThrownBy(() -> tool.execute(
                        "cancelled", Map.of("tool", "Query", "args", Map.of("value", "x")), cancelled, null))
                .isInstanceOf(InterruptedException.class);
        assertThat(client.executeCalls()).isZero();

        client.overrideCallResult(
                new com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolClient.ToolResult("failed", null, true));
        assertThatThrownBy(
                        () -> tool.execute("failed", Map.of("tool", "Query", "args", Map.of("value", "x")), null, null))
                .isInstanceOf(MateToolExecutionException.class);
        assertThat(client.executeCalls()).isEqualTo(1);
    }

    private MateToolSessionState state(
            List<com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool> agentTools,
            List<com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool> skillTools) {
        PreparedAgentRuntime prepared =
                runtime(agentTools, skillTools.isEmpty() ? List.of() : List.of(skill(skillTools)));
        return new MateToolsetFactory(client).createSession(prepared, credentials);
    }

    private static void assertRejected(CallTool tool, String name) {
        assertThatThrownBy(() -> tool.execute("call", Map.of("tool", name), null, null))
                .isInstanceOf(MateToolExecutionException.class);
    }
}
