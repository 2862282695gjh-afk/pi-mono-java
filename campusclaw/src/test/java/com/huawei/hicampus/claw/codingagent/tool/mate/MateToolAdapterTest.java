/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.AGENT_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.QUERY_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.SKILL_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.binding;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.meta;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.requiredValueSchema;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.runtime;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.skill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.tool.AgentContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;

import org.junit.jupiter.api.Test;

/**
 * {@link MateToolAdapter} 的定义、范围冲突和 Pipeline 校验测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolAdapterTest {

    @Test
    void shouldExposeActualDefinitionAndExecuteTrustedAgentTarget() throws Exception {
        MockMateToolClient client = clientWithQuery();
        MateCredentials credentials = MateCredentials.appKey("caller", "key", "access");
        var prepared = runtime(List.of(binding(QUERY_ID, "Query", "业务查询", "allow")), List.of());
        MateToolSessionState state = new MateToolsetFactory(client).createSession(prepared, credentials);

        MateToolAdapter adapter =
                (MateToolAdapter) state.createAgentToolAdapters().getFirst();
        var result = adapter.execute("call", Map.of("value", "x"), null, null);

        assertThat(adapter.name()).isEqualTo("Query");
        assertThat(adapter.label()).isEqualTo("业务查询");
        assertThat(adapter.description()).isEqualTo("Query records");
        assertThat(adapter.parameters())
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(requiredValueSchema()));
        assertThat(adapter.executionMode()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
        assertThat(result.content()).isNotEmpty();
        assertThat(client.lastCalledTool()).isEqualTo(QUERY_ID);
        assertThat(client.lastCallCredentials()).isSameAs(credentials);
    }

    @Test
    void proxySchemaFailureShouldSkipBeforeHookExecuteAndAfterHook() {
        MockMateToolClient client = clientWithQuery();
        var prepared = runtime(List.of(binding(QUERY_ID, "Query", "业务查询", "ask")), List.of());
        MateToolAdapter adapter = (MateToolAdapter) new MateToolsetFactory(client)
                .createSession(prepared, MateCredentials.empty())
                .createAgentToolAdapters()
                .getFirst();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger beforeCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        pipeline.setBeforeToolCall(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        pipeline.setAfterToolCall(context -> {
            afterCalls.incrementAndGet();
            return null;
        });

        var result = pipeline.execute(
                adapter,
                new ToolCall("call", "Query", Map.of()),
                Map.of(),
                new AgentContext(),
                new CancellationToken(),
                null);

        assertThat(result.isError()).isTrue();
        assertThat(beforeCalls).hasValue(0);
        assertThat(afterCalls).hasValue(0);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void directExecutorShouldKeepBusinessValidation() {
        MockMateToolClient client = clientWithQuery();
        var prepared = runtime(List.of(binding(QUERY_ID, "Query", "业务查询", "allow")), List.of());
        MateToolExecutor executor =
                new MateToolExecutor(client, MateCredentials.empty(), new MateToolResolver(client, prepared));

        assertThatThrownBy(() -> executor.executeAgent("Query", Map.of(), new CancellationToken()))
                .isInstanceOf(com.huawei.hicampus.claw.agent.tool.ToolArgumentsInvalidException.class);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void directAdapterExecutionShouldKeepBusinessValidation() {
        MockMateToolClient client = clientWithQuery();
        var prepared = runtime(List.of(binding(QUERY_ID, "Query", "业务查询", "allow")), List.of());
        MateToolAdapter adapter = (MateToolAdapter) new MateToolsetFactory(client)
                .createSession(prepared, MateCredentials.empty())
                .createAgentToolAdapters()
                .getFirst();

        assertThatThrownBy(() -> adapter.execute("call", Map.of(), new CancellationToken(), ignored -> {}))
                .isInstanceOf(com.huawei.hicampus.claw.agent.tool.ToolArgumentsInvalidException.class);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void proxyShouldExecutePreparedTargetAndArgumentsAfterHookStateChanges() {
        MockMateToolClient client = clientWithQuery();
        var runtime = runtime(List.of(binding(QUERY_ID, "Query", "业务查询", "ask")), List.of());
        MateToolAdapter adapter = (MateToolAdapter) new MateToolsetFactory(client)
                .createSession(runtime, MateCredentials.empty())
                .createAgentToolAdapters()
                .getFirst();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("value", "fixed");
        pipeline.setBeforeToolCall(context -> {
            assertThat(context.preparedInvocation().authorizationTargetId()).isEqualTo(QUERY_ID);
            arguments.put("value", "changed");
            client.bindAgent(AGENT_ID, List.of());
            return BeforeToolCallResult.allow();
        });

        var result = pipeline.execute(
                adapter,
                new ToolCall("call", "Query", arguments),
                arguments,
                new AgentContext(),
                new CancellationToken(),
                null);

        assertThat(result.isError()).isFalse();
        assertThat(client.executeCalls()).isEqualTo(1);
        assertThat(client.lastCalledTool()).isEqualTo(QUERY_ID);
        assertThat(client.lastCallArgs()).isEqualTo(Map.of("value", "fixed"));
    }

    @Test
    void skillOnlyToolsShouldNeverBecomeAdaptersAndOverlapShouldKeepBothPaths() throws Exception {
        MockMateToolClient client = clientWithQuery();
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
        var skillOnly = runtime(List.of(), List.of(skill(List.of(binding(QUERY_ID, "Query", "技能查询", "allow")))));
        MateToolSessionState skillState =
                new MateToolsetFactory(client).createSession(skillOnly, MateCredentials.empty());

        assertThat(skillState.createAgentToolAdapters()).isEmpty();
        skillState.createCallTool().execute("call", Map.of("tool", "Query", "args", Map.of("value", "x")), null, null);

        var overlap = runtime(
                List.of(binding(QUERY_ID, "Query", "代理查询", "allow")),
                List.of(skill(List.of(binding(QUERY_ID, "Query", "技能查询", "ask")))));
        MateToolSessionState overlapState =
                new MateToolsetFactory(client).createSession(overlap, MateCredentials.empty());
        assertThat(overlapState.createAgentToolAdapters())
                .extracting(tool -> tool.name())
                .containsExactly("Query");
        overlapState
                .createCallTool()
                .execute("call", Map.of("tool", "Query", "args", Map.of("value", "x")), null, null);
        assertThat(client.executeCalls()).isEqualTo(2);
    }

    @Test
    void shouldRejectFrameworkCollisionAndDifferentIdsWithoutSilentOverwrite() {
        MockMateToolClient client = clientWithQuery();
        String otherId = "tool-22222222222222222222222222222222";
        var frameworkCollision = runtime(List.of(binding(QUERY_ID, "Read", "Read", "allow")), List.of());
        var duplicate = runtime(
                List.of(binding(QUERY_ID, "Query", "One", "allow"), binding(otherId, "Query", "Two", "allow")),
                List.of());

        assertThatThrownBy(() -> new MateToolsetFactory(client)
                        .createSession(frameworkCollision, MateCredentials.empty())
                        .createAgentToolAdapters())
                .isInstanceOf(MateToolExecutionException.class)
                .hasMessageContaining("framework tool");
        assertThatThrownBy(() -> new MateToolsetFactory(client)
                        .createSession(duplicate, MateCredentials.empty())
                        .createAgentToolAdapters())
                .isInstanceOf(MateToolExecutionException.class)
                .hasMessageContaining("different trusted ids");
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void shouldRejectMissingBusinessSchemaBeforeProxyExposure() {
        MockMateToolClient client = new MockMateToolClient();
        client.addTool(meta(QUERY_ID, "Query", null));
        client.bindAgent(AGENT_ID, List.of(QUERY_ID));
        var prepared = runtime(List.of(binding(QUERY_ID, "Query", "业务查询", "allow")), List.of());

        assertThatThrownBy(() -> new MateToolsetFactory(client)
                        .createSession(prepared, MateCredentials.empty())
                        .createAgentToolAdapters())
                .isInstanceOf(MateToolExecutionException.class)
                .hasMessageContaining("business input schema is unavailable");
        assertThat(client.executeCalls()).isZero();
    }

    private static MockMateToolClient clientWithQuery() {
        MockMateToolClient client = new MockMateToolClient();
        client.addTool(meta(QUERY_ID, "Query", requiredValueSchema()));
        client.bindAgent(AGENT_ID, List.of(QUERY_ID));
        return client;
    }
}
