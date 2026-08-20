/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.campusclaw.codingagent.common.client.mate.MateToolMeta;
import com.campusclaw.codingagent.common.util.MateRestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * Stub-server tests for {@link HttpMateToolClient}'s two-step listTools:
 * agent/skill metadata GET (extracting bound tool IDs) followed by the
 * QUERYTOOLS POST, plus error and fallback branches.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class HttpMateToolClientTest {

    private MockWebServer server;

    private HttpMateToolClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new HttpMateToolClient(
                server.url("/").toString().replaceAll("/$", ""),
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

        assertThat(tools).extracting(MateToolMeta::name).containsExactly("query", "chart");
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

        assertThat(tools).extracting(MateToolMeta::name).containsExactly("query");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/mate-service/v1/skill/info/query/skill-11111111111111111111111111111111");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"tool-33333333333333333333333333333333\"");
    }

    @Test
    void emptyBindingToolsSkipsQueryTools() throws Exception {
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

        assertThat(tools).extracting(MateToolMeta::name).containsExactly("tool-44444444444444444444444444444444");
    }

    @Test
    void nonZeroResCodeOnMetadataThrows() {
        server.enqueue(json("{\"resCode\":\"500\",\"resMsg\":\"agent not found\",\"result\":null}"));

        assertThatThrownBy(() -> client.listTools("agent-11111111111111111111111111111111", null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("gateway call failed: resCode=500 resMsg=agent not found");
    }

    @Test
    void nonZeroResCodeOnQueryToolsThrows() {
        server.enqueue(
                json(
                        "{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"tool-11111111111111111111111111111111\"}]}}"));
        server.enqueue(json("{\"resCode\":\"403\",\"resMsg\":\"forbidden\",\"result\":null}"));

        assertThatThrownBy(() -> client.listTools("agent-11111111111111111111111111111111", null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("QUERYTOOLS failed: resCode=403 resMsg=forbidden");
    }

    @Test
    void invalidBoundToolIdIsRejectedBeforeQueryTools() {
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

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
