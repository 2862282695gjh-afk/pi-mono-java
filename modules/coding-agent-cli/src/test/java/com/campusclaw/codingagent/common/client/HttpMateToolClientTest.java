/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.common.util.MateRestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * {@link HttpMateToolClient} 两步查询的桩服务测试：先查询 Agent 或 Skill 元数据并提取绑定工具标识，
 * 再批量查询工具元数据，同时覆盖错误和兜底分支。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class HttpMateToolClientTest {

    private static final String AGENT_INFO_PATH_PREFIX = "/mate-service/v1/agents/";

    private static final String SKILL_TOOLS_QUERY_PATH_PREFIX = "/mate-service/v1/skill/info/query/";

    private static final String TOOL_METADATA_QUERY_PATH = "/mate-service/v1/runtime/tools/query";

    private static final String TOOL_EXECUTE_PATH_TEMPLATE = "/mate-service/v1/runtime/tools/%s/execute";

    private MockWebServer server;

    private HttpMateToolClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new HttpMateToolClient(
                server.url("/").toString().replaceAll("/$", ""),
                AGENT_INFO_PATH_PREFIX,
                SKILL_TOOLS_QUERY_PATH_PREFIX,
                TOOL_METADATA_QUERY_PATH,
                TOOL_EXECUTE_PATH_TEMPLATE,
                new MateRestUtil(),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void agentPathExtractsToolIdsThenQueriesTools() throws Exception {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":"
                                + "{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\",\"version\":\"1\"},{\"toolId\":\"tool-22222222222222222222222222222222\",\"version\":\"2\"}]}}"));
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                                + "{\"toolId\":\"tool-11111111111111111111111111111111\",\"toolName\":\"query\",\"description\":\"d1\",\"permission\":\"allow\"},"
                                + "{\"toolId\":\"tool-22222222222222222222222222222222\",\"toolName\":\"chart\",\"description\":\"d2\",\"permission\":\"deny\"}]}}"));

        List<MateToolMeta> tools = client.listTools("agent-11111111111111111111111111111111", null);

        assertThat(tools)
                .extracting(MateToolMeta::toolId)
                .containsExactly("tool-11111111111111111111111111111111", "tool-22222222222222222222222222222222");
        assertThat(tools.get(1).permission()).isEqualTo("deny");

        var agentReq = server.takeRequest();
        assertThat(agentReq.getMethod()).isEqualTo("GET");
        assertThat(agentReq.getPath()).isEqualTo("/mate-service/v1/agents/agent-11111111111111111111111111111111");
        var queryReq = server.takeRequest();
        assertThat(queryReq.getMethod()).isEqualTo("POST");
        assertThat(queryReq.getPath()).isEqualTo("/mate-service/v1/runtime/tools/query");
        assertThat(queryReq.getBody().readUtf8())
                .contains("\"tool-11111111111111111111111111111111\"")
                .contains("\"tool-22222222222222222222222222222222\"");
    }

    @Test
    void skillPathExtractsIdFieldThenQueriesTools() throws Exception {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":"
                                + "{\"bindingTools\":[{\"id\":\"tool-33333333333333333333333333333333\",\"name\":\"query\",\"permission\":\"allow\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                + "{\"toolId\":\"tool-33333333333333333333333333333333\",\"toolName\":\"query\"}]}}"));

        List<MateToolMeta> tools = client.listTools(null, "skill-11111111111111111111111111111111");

        assertThat(tools).extracting(MateToolMeta::toolId).containsExactly("tool-33333333333333333333333333333333");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/mate-service/v1/skill/info/query/skill-11111111111111111111111111111111");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"tool-33333333333333333333333333333333\"");
    }

    @Test
    void emptyBindingToolsSkipsToolMetadataQuery() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}"));

        List<MateToolMeta> tools = client.listTools("agent-11111111111111111111111111111111", null);

        assertThat(tools).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void missingToolNameFallsBackToToolId() throws Exception {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"tool-44444444444444444444444444444444\"}]}}"));
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":[{\"toolId\":\"tool-44444444444444444444444444444444\"}]}}"));

        List<MateToolMeta> tools = client.listTools("agent-11111111111111111111111111111111", null);

        assertThat(tools).extracting(MateToolMeta::toolId).containsExactly("tool-44444444444444444444444444444444");
    }

    @Test
    void nonZeroResCodeOnMetadataThrows() {
        server.enqueue(json("{\"resCode\":\"500\",\"resMsg\":\"agent not found\",\"result\":null}"));

        assertThatThrownBy(() -> client.listTools("agent-11111111111111111111111111111111", null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("gateway call failed: resCode=500 resMsg=agent not found");
    }

    @Test
    void nonZeroResCodeOnToolMetadataQueryThrows() {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"403\",\"resMsg\":\"forbidden\",\"result\":null}"));

        assertThatThrownBy(() -> client.listTools("agent-11111111111111111111111111111111", null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("tool metadata query failed: resCode=403 resMsg=forbidden");
    }

    @Test
    void invalidBoundToolIdIsRejectedBeforeToolMetadataQuery() {
        server.enqueue(json(
                "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"old-tool-id\"}]}}"));

        assertThatThrownBy(() -> client.listTools("agent-11111111111111111111111111111111", null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Invalid tool id: old-tool-id");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void maliciousAgentIdIsRejectedBeforeAnyRequest() {
        for (String bad : new String[] {"../admin", "a?x=1", "a%2Fb", "a b", ".hidden"}) {
            assertThatThrownBy(() -> client.listTools(bad, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void maliciousSkillIdIsRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.listTools(null, "../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void headerInfoFieldsAreSentAsHttpHeaders() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}"));

        client.listTools("agent-11111111111111111111111111111111", null);

        var request = server.takeRequest();
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
    }

    @Test
    void invokeToolPostsBodyAndForwardsCredentials() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"answer\":42}}"));

        MateToolClient.ToolResult result = client.callTool(
                "tool-11111111111111111111111111111111",
                java.util.Map.of("query", "hi"),
                MateCredentials.appKey("hw-id-1", "key-1"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("42");
        var request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
                .isEqualTo("/mate-service/v1/runtime/tools/tool-11111111111111111111111111111111/execute");
        assertThat(request.getBody().readUtf8()).contains("\"query\":\"hi\"");
        assertThat(request.getHeader("X-HW-ID")).isEqualTo("hw-id-1");
        assertThat(request.getHeader("X-HW-APPKEY")).isEqualTo("key-1");
        assertThat(request.getHeader("Authorization")).isNull();
    }

    @Test
    void invokeToolWithBlankCredentialsIsRefusedBeforeRequest() {
        for (MateCredentials bad : new MateCredentials[] {
            new MateCredentials(null, null, null),
            new MateCredentials("", "", ""),
            MateCredentials.appKey("hw-id-1", ""),
            new MateCredentials("hw-id-1", "key", "Bearer tok")
        }) {
            MateToolClient.ToolResult result =
                    client.callTool("tool-11111111111111111111111111111111", java.util.Map.of(), bad);
            assertThat(result.isError()).isTrue();
            assertThat(result.content()).contains("incomplete credentials");
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void invokeToolWithoutCredentialsIsRefusedBeforeRequest() {
        MateToolClient.ToolResult result =
                client.callTool("tool-11111111111111111111111111111111", java.util.Map.of(), null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("incomplete credentials");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void discoveredToolIdSatisfiesExecuteContract() throws Exception {
        // Contract: the toolId returned by listTools must be directly usable
        // as the `tool` parameter of callTool (discovery-to-execution).
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":"
                + "{\"bindingTools\":[{\"toolId\":\"tool-aaaabbbbccccddddeeeeffff00001111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                + "{\"toolId\":\"tool-aaaabbbbccccddddeeeeffff00001111\",\"toolName\":\"query\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"answer\":1}}"));

        List<MateToolMeta> tools = client.listTools("agent-11111111111111111111111111111111", null);
        String discoveredId = tools.getFirst().toolId();
        MateToolClient.ToolResult result =
                client.callTool(discoveredId, java.util.Map.of(), MateCredentials.appKey("hw-id-1", "key-1"));

        assertThat(result.isError()).isFalse();
        assertThat(server.takeRequest().getPath()).contains("/agents/");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mate-service/v1/runtime/tools/query");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/mate-service/v1/runtime/tools/tool-aaaabbbbccccddddeeeeffff00001111/execute");
    }

    @Test
    void jwtFactoryRejectsBlankTokens() {
        assertThatThrownBy(() -> MateCredentials.jwt("hw-id-1", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MateCredentials.jwt("hw-id-1", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MateCredentials.jwt("hw-id-1", "   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invokeToolJwtModeForwardsAuthorizationHeader() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"answer\":1}}"));

        client.callTool(
                "tool-11111111111111111111111111111111",
                java.util.Map.of(),
                MateCredentials.jwt("hw-id-2", "jwt-token"));

        var request = server.takeRequest();
        assertThat(request.getHeader("X-HW-ID")).isEqualTo("hw-id-2");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer jwt-token");
        assertThat(request.getHeader("X-HW-APPKEY")).isNull();
    }

    @Test
    void invokeToolNonZeroResCodeReturnsErrorResult() throws Exception {
        server.enqueue(json("{\"resCode\":\"430\",\"resMsg\":\"not authorized\",\"result\":null}"));

        MateToolClient.ToolResult result = client.callTool(
                "tool-11111111111111111111111111111111",
                java.util.Map.of(),
                MateCredentials.appKey("hw-id-1", "key-1"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("430").contains("not authorized");
    }

    @Test
    void invokeToolRejectsMaliciousToolIdBeforeRequest() {
        MateToolClient.ToolResult result = client.callTool("../admin", java.util.Map.of(), null);
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Invalid tool id");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void configuredEndpointPathsAreUsed() throws Exception {
        HttpMateToolClient configuredClient = new HttpMateToolClient(
                server.url("/").toString().replaceAll("/$", ""),
                "/custom/agents/",
                "/custom/skills/",
                "/custom/tools/query",
                "/custom/tools/%s/execute",
                new MateRestUtil(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":[]}}"));

        configuredClient.listTools("agent-11111111111111111111111111111111", null);

        assertThat(server.takeRequest().getPath()).isEqualTo("/custom/agents/agent-11111111111111111111111111111111");
        assertThat(server.takeRequest().getPath()).isEqualTo("/custom/tools/query");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
