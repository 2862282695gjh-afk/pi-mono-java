/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CallMateTool} 的缓存命中、自动刷新、single-flight 和不重放测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class CallMateToolTest {

    private static final String QUERY_ID = "tool-11111111111111111111111111111111";

    private static final MateCredentials CREDENTIALS = MateCredentials.jwt("caller-1", "token-1");

    private MockMateToolClient client;

    private MateToolSessionState state;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        client.addTool(meta(QUERY_ID, "Query"));
        client.bindAgent("agent-1", List.of(QUERY_ID));
        client.bindSkill("skill-1", List.of());
        state = new MateToolsetFactory(client).createSession("agent-1", Map.of("research", "skill-1"), CREDENTIALS);
    }

    @Test
    void shouldPublishPascalCaseSequentialContract() {
        CallMateTool tool = state.createCallTool();

        assertThat(tool.name()).isEqualTo("CallMateTool");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
        assertThat(tool.parameters().path("required").get(0).asText()).isEqualTo("tool");
        assertThat(tool.parameters().path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void cacheMissShouldRefreshAllSourcesThenExecuteOnce() throws Exception {
        state.createCallTool().execute("call", Map.of("tool", "Query"), null, null);

        assertThat(client.agentListCalls()).isEqualTo(1);
        assertThat(client.skillListCalls()).isEqualTo(1);
        assertThat(client.executeCalls()).isEqualTo(1);
        assertThat(client.lastCalledTool()).isEqualTo(QUERY_ID);
        assertThat(client.lastCallArgs()).isEmpty();
        assertThat(client.lastCallCredentials()).isSameAs(CREDENTIALS);
        assertThat(client.lastListCredentials()).isSameAs(CREDENTIALS);
    }

    @Test
    void listHitShouldAvoidAutomaticFullRefresh() throws Exception {
        state.createListTool().execute("list", Map.of(), null, null);
        state.createCallTool().execute("call", Map.of("tool", "Query"), null, null);

        assertThat(client.agentListCalls()).isEqualTo(1);
        assertThat(client.skillListCalls()).isZero();
        assertThat(client.executeCalls()).isEqualTo(1);
    }

    @Test
    void executeFailureShouldNotReplayDiscoveryOrExecution() {
        client.overrideCallResult(new MateToolClient.ToolResult("failed", null, true));

        assertThatThrownBy(() -> state.createCallTool().execute("call", Map.of("tool", "Query"), null, null))
                .isInstanceOf(CallMateTool.MateToolExecutionException.class);
        assertThat(client.agentListCalls()).isEqualTo(1);
        assertThat(client.skillListCalls()).isEqualTo(1);
        assertThat(client.executeCalls()).isEqualTo(1);
    }

    @Test
    void missingCredentialsShouldFailBeforeExecutionRequest() {
        MateToolSessionState missingCredentials =
                new MateToolsetFactory(client).createSession("agent-1", Map.of(), MateCredentials.empty());

        assertThatThrownBy(
                        () -> missingCredentials.createCallTool().execute("call", Map.of("tool", "Query"), null, null))
                .isInstanceOf(CallMateTool.MateToolExecutionException.class)
                .hasMessageContaining("credentials are unavailable");
        assertThat(client.agentListCalls()).isZero();
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void concurrentMissesShouldShareOneFullRefresh() throws Exception {
        BlockingMateClient blocking = new BlockingMateClient(client);
        MateToolSessionState blockingState =
                new MateToolsetFactory(blocking).createSession("agent-1", Map.of("research", "skill-1"), CREDENTIALS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> call(blockingState));
            blocking.started.await(5, TimeUnit.SECONDS);
            var second = executor.submit(() -> call(blockingState));
            blocking.release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertThat(blocking.agentLists).isEqualTo(1);
        assertThat(blocking.skillLists).isEqualTo(1);
        assertThat(blocking.executeCalls).isEqualTo(2);
    }

    @Test
    void conflictingNamesShouldFailAtomicallyBeforeExecution() {
        String otherId = "tool-22222222222222222222222222222222";
        client.addTool(meta(otherId, "Query"));
        client.bindSkill("skill-1", List.of(otherId));

        assertThatThrownBy(() -> state.createCallTool().execute("call", Map.of("tool", "Query"), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different ids");
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void sameNameAndIdAcrossSourcesShouldDeduplicateAndExecuteOnce() throws Exception {
        client.bindSkill("skill-1", List.of(QUERY_ID));

        state.createCallTool().execute("call", Map.of("tool", "Query"), null, null);

        assertThat(client.agentListCalls()).isEqualTo(1);
        assertThat(client.skillListCalls()).isEqualTo(1);
        assertThat(client.executeCalls()).isEqualTo(1);
    }

    private static MateToolMeta meta(String id, String name) {
        return new MateToolMeta(id, name, "description", Map.of(), Map.of(), true, "allow");
    }

    private static void call(MateToolSessionState state) {
        try {
            state.createCallTool().execute("call", Map.of("tool", "Query"), null, null);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class BlockingMateClient implements MateToolClient {

        private final MockMateToolClient delegate;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private int agentLists;
        private int skillLists;
        private int executeCalls;

        private BlockingMateClient(MockMateToolClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized List<MateToolMeta> listAgentTools(String agentId, MateCredentials credentials) {
            agentLists++;
            started.countDown();
            awaitRelease();
            return delegate.listAgentTools(agentId, credentials);
        }

        @Override
        public synchronized List<MateToolMeta> listSkillTools(String skillId, MateCredentials credentials) {
            skillLists++;
            return delegate.listSkillTools(skillId, credentials);
        }

        @Override
        public synchronized ToolResult callTool(
                String tool,
                Map<String, Object> args,
                com.campusclaw.codingagent.common.client.mate.MateCredentials credentials) {
            executeCalls++;
            return delegate.callTool(tool, args, credentials);
        }

        private void awaitRelease() {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }
}
