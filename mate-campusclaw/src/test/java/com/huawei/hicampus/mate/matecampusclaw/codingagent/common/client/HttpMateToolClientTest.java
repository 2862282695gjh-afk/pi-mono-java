/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolResponseException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.util.MateRestUtil;

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

    private static final String AGENT_INFO_PATH_TEMPLATE = "/mate-service/v1/agents/%s";

    private static final String SKILL_INFO_PATH_TEMPLATE = "/mate-service/v1/skill/query/%s";

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
                AGENT_INFO_PATH_TEMPLATE,
                SKILL_INFO_PATH_TEMPLATE,
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
                                + "{\"id\":\"tool-22222222222222222222222222222222\",\"name\":\"chart\",\"description\":\"d2\",\"permission\":\"deny\",\"is_concurrency_safe\":false},"
                                + "{\"id\":\"tool-11111111111111111111111111111111\",\"name\":\"query\",\"description\":\"d1\",\"permission\":\"allow\",\"is_concurrency_safe\":true,\"display_name\":\"Query\",\"input_schema\":{\"type\":\"object\"},\"output_schema\":{\"type\":\"object\"}}]}}"));

        List<MateToolMeta> tools =
                client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty());

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
                                + "{\"bindingTools\":[{\"id\":\"tool-33333333333333333333333333333333\",\"name\":\"query\",\"permission\":\"allow\",\"is_concurrency_safe\":true}]}}"));
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                                + "{\"id\":\"tool-33333333333333333333333333333333\",\"name\":\"query\",\"display_name\":\"Query Skill\"}]}}"));

        List<MateToolMeta> tools =
                client.listSkillTools("skill-11111111111111111111111111111111", MateCredentials.empty());

        assertThat(tools).extracting(MateToolMeta::toolId).containsExactly("tool-33333333333333333333333333333333");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/mate-service/v1/skill/query/skill-11111111111111111111111111111111");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"tool-33333333333333333333333333333333\"");
    }

    @Test
    void emptySkillInfoObjectReturnsEmptyToolList() throws Exception {
        // result: {} — bindingTools 字段被省略
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{}}"));

        List<MateToolMeta> tools =
                client.listSkillTools("skill-11111111111111111111111111111111", MateCredentials.empty());

        assertThat(tools).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void nullBindingToolsReturnsEmptyToolList() throws Exception {
        // result: {"bindingTools": null} — 显式空值
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":null}}"));

        List<MateToolMeta> tools =
                client.listSkillTools("skill-11111111111111111111111111111111", MateCredentials.empty());

        assertThat(tools).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void emptyBindingToolsSkipsToolMetadataQuery() throws Exception {
        // skill 路径 bindingTools 直挂 result(对齐 runtime 契约),空列表时跳过工具元数据查询
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}"));

        List<MateToolMeta> tools =
                client.listSkillTools("skill-11111111111111111111111111111111", MateCredentials.empty());

        assertThat(tools).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void emptyAgentBindingToolsSkipsToolMetadataQuery() throws Exception {
        // agent 路径契约不变:bindingTools 直挂 result
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}"));

        List<MateToolMeta> tools =
                client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty());

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
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":[{\"id\":\"tool-44444444444444444444444444444444\"}]}}"));

        List<MateToolMeta> tools =
                client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty());

        assertThat(tools).extracting(MateToolMeta::toolId).containsExactly("tool-44444444444444444444444444444444");
    }

    @Test
    void nonZeroResCodeWithParseableResultSucceeds() throws Exception {
        // 客户端不按 resCode 预判处理结果:result 可解析即成功。
        server.enqueue(json("{\"resCode\":\"500\",\"resMsg\":\"partial outage\",\"result\":"
                + "{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"403\",\"resMsg\":\"forbidden\",\"result\":{\"data\":"
                + "[{\"id\":\"tool-11111111111111111111111111111111\",\"name\":\"query\"}]}}"));

        List<MateToolMeta> tools =
                client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty());

        assertThat(tools).extracting(MateToolMeta::toolId).containsExactly("tool-11111111111111111111111111111111");
    }

    @Test
    void emptyResponseBodyOnAgentInfoThrowsMateToolResponseException() {
        server.enqueue(json(""));

        assertThatThrownBy(
                        () -> client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty()))
                .isInstanceOf(MateToolResponseException.class)
                .hasMessageContaining("response body is empty");
    }

    @Test
    void missingResultOnAgentInfoThrowsInsteadOfEmptyTools() {
        // result 缺失/null 不允许折叠成"没有绑定工具"。
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\"}"));

        assertThatThrownBy(
                        () -> client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty()))
                .isInstanceOf(MateToolResponseException.class)
                .hasMessageContaining("result is missing or null");
    }

    @Test
    void missingResultDataOnToolMetadataQueryThrowsWithStableCode() {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"403\",\"resMsg\":\"forbidden\",\"result\":null}"));

        assertThatThrownBy(
                        () -> client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty()))
                .isInstanceOf(MateToolResponseException.class)
                .hasMessageContaining("result.data is missing")
                .extracting(e -> ((MateToolResponseException) e).stableErrorCode())
                .isEqualTo("MATE_TOOL_RESPONSE_INVALID");
    }

    @Test
    void invalidBoundToolIdIsRejectedBeforeToolMetadataQuery() {
        server.enqueue(json(
                "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"old-tool-id\"}]}}"));

        assertThatThrownBy(
                        () -> client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Invalid tool id: old-tool-id");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void maliciousAgentIdIsRejectedBeforeAnyRequest() {
        for (String bad : new String[] {"../admin", "a?x=1", "a%2Fb", "a b", ".hidden"}) {
            assertThatThrownBy(() -> client.listAgentTools(bad, MateCredentials.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void maliciousSkillIdIsRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.listSkillTools("../etc/passwd", MateCredentials.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void discoveryForwardsExecutionCredentials() throws Exception {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\"}]}}"));
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":[{\"id\":\"tool-11111111111111111111111111111111\",\"name\":\"query\"}]}}"));

        MateCredentials credentials = new MateCredentials("caller-1", "app-key-1", "Bearer token-1");
        client.listAgentTools("agent-11111111111111111111111111111111", credentials);

        assertCredentialHeaders(server.takeRequest(), credentials);
        assertCredentialHeaders(server.takeRequest(), credentials);
    }

    @Test
    void skillDiscoveryForwardsExecutionCredentials() throws Exception {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"id\":\"tool-22222222222222222222222222222222\"}]}}"));
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":[{\"id\":\"tool-22222222222222222222222222222222\",\"name\":\"search\"}]}}"));

        MateCredentials credentials = new MateCredentials("caller-2", "app-key-2", "Bearer token-2");
        client.listSkillTools("skill-11111111111111111111111111111111", credentials);

        assertCredentialHeaders(server.takeRequest(), credentials);
        assertCredentialHeaders(server.takeRequest(), credentials);
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
            MateCredentials.appKey("hw-id-1", "")
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
        // 发现返回的 toolId 必须能够直接作为 callTool 的工具标识。
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":"
                + "{\"bindingTools\":[{\"toolId\":\"tool-aaaabbbbccccddddeeeeffff00001111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                + "{\"id\":\"tool-aaaabbbbccccddddeeeeffff00001111\",\"name\":\"query\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"answer\":1}}"));

        List<MateToolMeta> tools =
                client.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty());
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
    void invokeToolForwardsCoexistingUpstreamCredentials() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"answer\":1}}"));
        MateCredentials credentials = new MateCredentials("hw-id-3", "app-key-3", "Bearer jwt-token-3");

        MateToolClient.ToolResult result =
                client.callTool("tool-11111111111111111111111111111111", java.util.Map.of(), credentials);

        assertThat(result.isError()).isFalse();
        var request = server.takeRequest();
        assertThat(request.getHeader("X-HW-ID")).isEqualTo("hw-id-3");
        assertThat(request.getHeader("X-HW-APPKEY")).isEqualTo("app-key-3");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer jwt-token-3");
    }

    @Test
    void credentialsToStringDoesNotExposeSecrets() {
        MateCredentials credentials = new MateCredentials("caller-secret", "appkey-secret", "Bearer jwt-secret");

        assertThat(credentials.toString())
                .contains("xHwIdPresent=true", "xHwAppKeyPresent=true", "authorizationPresent=true")
                .doesNotContain("caller-secret", "appkey-secret", "jwt-secret");
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
        assertThat(result.content()).isEqualTo("Mate tool execution request failed");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void configuredEndpointPathsAreUsed() throws Exception {
        HttpMateToolClient configuredClient = new HttpMateToolClient(
                server.url("/").toString().replaceAll("/$", ""),
                "/mate-service/custom%20segment/agents/%s",
                "/mate-service/custom/skills/%s",
                "/mate-service/custom/tools/query",
                "/mate-service/custom/tools/%s/execute",
                new MateRestUtil(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                + "{\"id\":\"tool-11111111111111111111111111111111\",\"name\":\"query\"}]}}"));

        configuredClient.listAgentTools("agent-11111111111111111111111111111111", MateCredentials.empty());

        assertThat(server.takeRequest().getPath())
                .isEqualTo("/mate-service/custom%20segment/agents/agent-11111111111111111111111111111111");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mate-service/custom/tools/query");
    }

    @Test
    void percentEncodedSkillAndToolTemplatesExpandOnlyLiteralPlaceholder() throws Exception {
        HttpMateToolClient configuredClient = new HttpMateToolClient(
                server.url("/").toString().replaceAll("/$", ""),
                AGENT_INFO_PATH_TEMPLATE,
                "/mate-service/custom%20segment/skills/%s",
                TOOL_METADATA_QUERY_PATH,
                "/mate-service/custom%20segment/tools/%s/execute",
                new MateRestUtil(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"answer\":1}}"));

        configuredClient.listSkillTools("skill-11111111111111111111111111111111", MateCredentials.empty());
        configuredClient.callTool(
                "tool-11111111111111111111111111111111",
                java.util.Map.of(),
                MateCredentials.appKey("hw-id-1", "key-1"));

        assertThat(server.takeRequest().getPath())
                .isEqualTo("/mate-service/custom%20segment/skills/skill-11111111111111111111111111111111");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/mate-service/custom%20segment/tools/tool-11111111111111111111111111111111/execute");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static void assertCredentialHeaders(
            okhttp3.mockwebserver.RecordedRequest request, MateCredentials credentials) {
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
        assertThat(request.getHeader("X-HW-ID")).isEqualTo(credentials.xHwId());
        assertThat(request.getHeader("X-HW-APPKEY")).isEqualTo(credentials.xHwAppKey());
        assertThat(request.getHeader("Authorization")).isEqualTo(credentials.authorization());
    }
}
