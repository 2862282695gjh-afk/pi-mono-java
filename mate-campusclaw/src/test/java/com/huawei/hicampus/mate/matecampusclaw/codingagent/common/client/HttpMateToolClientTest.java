/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolMeta;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.util.MateRestUtil;

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
                                + "{\"bindingTools\":[{\"toolId\":\"t-1\",\"version\":\"1\"},{\"toolId\":\"t-2\",\"version\":\"2\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                + "{\"toolId\":\"t-1\",\"toolName\":\"query\",\"description\":\"d1\",\"permission\":\"allow\"},"
                + "{\"toolId\":\"t-2\",\"toolName\":\"chart\",\"description\":\"d2\",\"permission\":\"deny\"}]}}"));

        List<MateToolMeta> tools = client.listTools("agent-1", null);

        assertThat(tools).extracting(MateToolMeta::name).containsExactly("query", "chart");
        assertThat(tools.get(1).permission()).isEqualTo("deny");

        var agentReq = server.takeRequest();
        assertThat(agentReq.getMethod()).isEqualTo("GET");
        assertThat(agentReq.getPath()).isEqualTo("/mate-service/v1/agents/agent-1");
        var queryReq = server.takeRequest();
        assertThat(queryReq.getMethod()).isEqualTo("POST");
        assertThat(queryReq.getPath()).isEqualTo("/mate-service/v1/runtime/tools/query");
        assertThat(queryReq.getBody().readUtf8()).contains("\"t-1\"").contains("\"t-2\"");
    }

    @Test
    void skillPathExtractsIdFieldThenQueriesTools() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":"
                + "{\"bindingTools\":[{\"id\":\"s-1\",\"name\":\"query\",\"permission\":\"allow\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":["
                + "{\"toolId\":\"s-1\",\"toolName\":\"query\"}]}}"));

        List<MateToolMeta> tools = client.listTools(null, "skill-1");

        assertThat(tools).extracting(MateToolMeta::name).containsExactly("query");
        assertThat(server.takeRequest().getPath()).isEqualTo("/mate-service/v1/skill/info/query/skill-1");
        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"s-1\"");
    }

    @Test
    void emptyBindingToolsSkipsQueryTools() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}"));

        List<MateToolMeta> tools = client.listTools("agent-1", null);

        assertThat(tools).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void missingToolNameFallsBackToToolId() throws Exception {
        server.enqueue(
                json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"raw-id\"}]}}"));
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"data\":[{\"toolId\":\"raw-id\"}]}}"));

        List<MateToolMeta> tools = client.listTools("agent-1", null);

        assertThat(tools).extracting(MateToolMeta::name).containsExactly("raw-id");
    }

    @Test
    void nonZeroResCodeOnMetadataThrows() {
        server.enqueue(json("{\"resCode\":\"500\",\"resMsg\":\"agent not found\",\"result\":null}"));

        assertThatThrownBy(() -> client.listTools("agent-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("gateway call failed: resCode=500 resMsg=agent not found");
    }

    @Test
    void nonZeroResCodeOnQueryToolsThrows() {
        server.enqueue(
                json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[{\"toolId\":\"t-1\"}]}}"));
        server.enqueue(json("{\"resCode\":\"403\",\"resMsg\":\"forbidden\",\"result\":null}"));

        assertThatThrownBy(() -> client.listTools("agent-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("QUERYTOOLS failed: resCode=403 resMsg=forbidden");
    }

    @Test
    void headerInfoFieldsAreSentAsHttpHeaders() throws Exception {
        server.enqueue(json("{\"resCode\":\"0\",\"resMsg\":\"ok\",\"result\":{\"bindingTools\":[]}}"));

        client.listTools("agent-1", null);

        var request = server.takeRequest();
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
