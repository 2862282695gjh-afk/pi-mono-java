/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.AGENT_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CHAT_PATH;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.CLIENT;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MAPPER;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.MODEL_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SECOND_MODEL_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SKILL_ID;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.SKILL_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeManager;
import com.huawei.hicampus.claw.codingagent.runtime.AgentRuntimeProperties;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.huawei.hicampus.claw.codingagent.runtimeapi.web.RuntimeHttpProcessFixture.ModelStub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证测试辅助代码准备的完整 Agent 快照、模拟模型响应及资源关闭行为。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeHttpProcessFixtureTest {
    @Test
    void testCompleteCachePreservesSkillBinding(@TempDir Path tempDir) throws Exception {
        RuntimeHttpProcessFixture.prepareRuntimeFiles(tempDir);
        MateServiceClient remote = mock(MateServiceClient.class);
        AgentRuntimeManager manager = manager(tempDir, remote);

        var snapshot = manager.prepareCached(AGENT_ID);
        assertThat(snapshot.agentId()).isEqualTo(AGENT_ID);
        assertThat(snapshot.metadata().displayName()).isEqualTo("跨进程测试助手");
        assertThat(snapshot.metadata().description()).containsExactly("分析订单并生成摘要");
        assertThat(snapshot.metadata().userCases()).containsExactly("请分析本次订单");
        assertThat(snapshot.metadata().bindingModels()).containsExactly(MODEL_ID, SECOND_MODEL_ID);
        assertThat(snapshot.bindingAgents()).isEmpty();
        assertThat(snapshot.skillIdsByName()).containsOnlyKeys(SKILL_NAME).containsEntry(SKILL_NAME, SKILL_ID);
        var skill = snapshot.findSkill(SKILL_NAME).orElseThrow();
        assertThat(skill.version()).isEqualTo("1");
        assertThat(skill.description()).isEqualTo("分析测试订单");
        assertThat(skill.content()).contains("disable-model-invocation: true", "Inspect the requested orders");
        assertThat(new RuntimeAgentPromptLoader().load(snapshot.agentRoot().resolve(".campusclaw")))
                .contains("Deterministic process test agent.")
                .doesNotContain("分析测试订单");
        assertThat(manager(tempDir, remote).prepareCached(AGENT_ID).skillIdsByName())
                .isEqualTo(snapshot.skillIdsByName());
        verifyNoInteractions(remote);
    }

    @Test
    void testIncompleteFixtureIsRejectedWithoutRemoteCall(@TempDir Path tempDir) throws Exception {
        RuntimeHttpProcessFixture.prepareRuntimeFiles(tempDir);
        Files.delete(tempDir.resolve("agent")
                .resolve(AGENT_ID)
                .resolve(".campusclaw/skills")
                .resolve(SKILL_NAME)
                .resolve("skill.json"));
        MateServiceClient remote = mock(MateServiceClient.class);
        assertThat(manager(tempDir, remote).prepareCached(AGENT_ID)).isNull();
        verifyNoInteractions(remote);
    }

    @Test
    void testModelStubGatesRealRequestAndCapturesBody() throws Exception {
        int port = RuntimeHttpProcessFixture.freePort();
        ModelStub stub = new ModelStub(port);
        try (stub) {
            stub.start();
            var gate = stub.blockNextResponse();
            var response = CLIENT.sendAsync(
                    request(port, CHAT_PATH), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            gate.awaitRequest();
            assertThat(response).isNotDone();
            assertThat(stub.lastRequest().path("model").asText()).isEqualTo(MODEL_ID);
            assertThat(stub.requestCount()).isOne();
            assertThat(stub.responseCount()).isZero();
            gate.release();
            var completed = response.get(5, TimeUnit.SECONDS);
            assertThat(stub.responseCount()).isOne();
            assertThat(completed.statusCode()).isEqualTo(200);
            assertThat(completed.headers().firstValue("Content-Type")).contains("text/event-stream");
            assertThat(completed.body()).contains("process-level answer", "data: [DONE]");
            assertThat(stub.requestPath()).isEqualTo(CHAT_PATH);
        }
        assertThat(stub.executorTerminated()).isTrue();
    }

    @Test
    void testCloseInterruptsGateAlreadyOwnedByHandler() throws Exception {
        int port = RuntimeHttpProcessFixture.freePort();
        ModelStub stub = new ModelStub(port);
        try (stub) {
            stub.start();
            var gate = stub.blockNextResponse();
            var response = CLIENT.sendAsync(
                    request(port, CHAT_PATH), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            gate.awaitRequest();
            stub.close();
            assertThat(stub.executorTerminated()).isTrue();
            var error = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
            assertThat(error.getCause()).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void testModelStubDoesNotPretendToProvideManagerMetadata() throws Exception {
        int port = RuntimeHttpProcessFixture.freePort();
        try (ModelStub stub = new ModelStub(port)) {
            stub.start();
            assertThat(stub.requestCount()).isZero();
            var response = CLIENT.send(
                    request(port, "/mate-service/v1/agents/" + AGENT_ID),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEmpty();
            assertThat(stub.requestCount()).isOne();
        }
    }

    private static AgentRuntimeManager manager(Path tempDir, MateServiceClient remote) {
        return new AgentRuntimeManager(
                new AgentRuntimeProperties(tempDir.resolve("agent"), null, null), remote, MAPPER);
    }

    private static HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + MODEL_ID + "\"}", StandardCharsets.UTF_8))
                .build();
    }
}
