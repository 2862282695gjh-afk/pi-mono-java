/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.config.CampusMateClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class MateServiceClientTest {

    private static final String AGENT_RUNTIME_PATH_TEMPLATE = "/mate-service/v1/agents/%s/runtime";

    private static final String SKILL_INFO_QUERY_PATH_TEMPLATE = "/mate-service/v1/skill/query/%s";

    private MockWebServer server;

    private MateServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        var properties = new AgentRuntimeProperties(Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        client = new MateServiceClient(properties, campusMateProperties(), new ObjectMapper());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void readsDirectRuntimeAndAcceptsSingularBindingSkill() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
{"resCode":"0","result":{
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingModels": "gpt-4o",
                          "bindingSkills": {
                            "id": "skill-11111111111111111111111111111111",
                            "version": "1.0.0"
                          },
                          "bindingTools": []
                        }}
"""));

        var runtime = client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", runtime.id());
        assertEquals(
                List.of("skill-11111111111111111111111111111111"),
                runtime.bindingSkills().stream()
                        .map(MateServiceClient.SkillReference::id)
                        .toList());
        assertEquals(
                "/mate-service/v1/agents/agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/runtime",
                server.takeRequest().getPath());
    }

    @Test
    void readsBindingModelsAndDescriptionLists() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
{"resCode":"0","result":{
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingModels": ["glm-5.2", "minimax-m2.5"],
                          "description": ["Diagnoses device faults", "Drafts reports"]
                        }}
"""));

        var runtime = client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(List.of("glm-5.2", "minimax-m2.5"), runtime.bindingModels());
        assertEquals(List.of("Diagnoses device faults", "Drafts reports"), runtime.description());
        assertEquals("glm-5.2", runtime.defaultModel().orElseThrow());
    }

    @Test
    void acceptsSingularBindingModelsAndDescription() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
{"resCode":"0","result":{
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingModels": "glm-5.2",
                          "description": "Diagnoses device faults"
                        }}
"""));

        var runtime = client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(List.of("glm-5.2"), runtime.bindingModels());
        assertEquals(List.of("Diagnoses device faults"), runtime.description());
    }

    @Test
    void readsBindingAgentsArrayWithDescriptionAndEnabledFlag() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
{"resCode":"0","result":{
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingAgents": [
                            {
                              "id": "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                              "name": "field-ops",
                              "displayName": "Field Ops Agent",
                              "description": "Handles on-site device operations",
                              "version": "2.0.0"
                            },
                            {
                              "id": "agent-cccccccccccccccccccccccccccccccc",
                              "name": "reporting"
                            }
                          ],
                          "enabled": false
                        }}
"""));

        var runtime = client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(2, runtime.bindingAgents().size());
        assertEquals(
                "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                runtime.bindingAgents().getFirst().id());
        assertEquals(
                "Handles on-site device operations",
                runtime.bindingAgents().getFirst().description());
        assertEquals("2.0.0", runtime.bindingAgents().getFirst().version());
        assertEquals(
                "agent-cccccccccccccccccccccccccccccccc",
                runtime.bindingAgents().get(1).id());
        assertEquals(Boolean.FALSE, runtime.enabled());
    }

    @Test
    void acceptsSingularBindingAgentAndDefaultsEnabledToTrue() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
{"resCode":"0","result":{
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingAgents": {
                            "id": "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            "name": "field-ops",
                            "description": "Handles on-site device operations"
                          }
                        }}
"""));

        var runtime = client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(1, runtime.bindingAgents().size());
        assertEquals(
                "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                runtime.bindingAgents().getFirst().id());
        assertEquals(
                "Handles on-site device operations",
                runtime.bindingAgents().getFirst().description());
        assertEquals(Boolean.TRUE, runtime.enabled());
    }

    @Test
    void queriesCompleteSkillInfoObjectWithVersionAndContent() throws Exception {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(skillInfoResponse()));

        var skill = client.querySkillInfo("skill-11111111111111111111111111111111");

        assertEquals("1.0.0", skill.version());
        assertEquals("---\nname: skill-a\ndescription: Skill A\n---\n", skill.content());
        assertEquals("calendar", skill.bindingTools().getFirst().name());
        assertEquals("base-skill", skill.bindingSkills().getFirst().name());
        assertEquals("template body", skill.templates().getFirst().content());
        assertEquals("reference body", skill.references().getFirst().content());
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/mate-service/v1/skill/query/skill-11111111111111111111111111111111", request.getPath());
        assertEquals(0L, request.getBody().size());
    }

    private static String skillInfoResponse() {
        return """
                {"resCode":"0","resMsg":"ok","result":{
                  "name":"skill-a","id":"skill-11111111111111111111111111111111","version":"1.0.0",
                  "description":"Skill A","useCases":"booking",
                  "content":"---\\nname: skill-a\\ndescription: Skill A\\n---\\n",
                  "bindingTools":[{"id":"tool-11111111111111111111111111111111","version":"2.0.0","name":"calendar",
                    "description":"Calendar tool","permission":"allow","source":"local"}],
                  "bindingSkills":[{"id":"skill-00000000000000000000000000000000","version":"0.9.0",
                    "name":"base-skill","description":"Base"}],
                  "templates":[{"id":"template-1","name":"request",
                    "content":"template body","fileType":"md"}],
                  "references":[{"id":"reference-1","name":"guide",
                    "content":"reference body","fileType":"txt"}]
                }}
                """;
    }

    @Test
    void rejectsArraySkillInfoResult() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"resCode\":\"0\",\"result\":[]}"));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.querySkillInfo("skill-11111111111111111111111111111111"));

        assertEquals("querySkillInfo result must be an object", error.getMessage());
    }

    @Test
    void acceptsNonZeroResCodeWhenResultIsParseable() throws Exception {
        // 客户端不按 resCode 预判处理结果：只要 result 可解析即成功。
        server.enqueue(
                json(
                        """
                {
                  "resCode": "72",
                  "resMsg": "ut laboris",
                  "result": {
                    "name": "calendar",
                    "id": "skill-11111111111111111111111111111111",
                    "version": "1.0.0",
                    "content": "---\\nname: calendar\\ndescription: Calendar\\n---\\nBody\\n"
                  }
                }
                """));

        MateServiceClient.SkillInfo skill = client.querySkillInfo("skill-11111111111111111111111111111111");

        assertEquals("calendar", skill.name());
        assertEquals("1.0.0", skill.version());
    }

    @Test
    void emptyResponseBodyOnGetAgentRuntimeFailsAsResponseInvalid() {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(""));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals(AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, error.errorCode());
        assertEquals(AgentRuntimeErrorCode.MATE_RESPONSE_INVALID.name(), error.stableErrorCode());
    }

    @Test
    void emptyResponseBodyOnQuerySkillInfoFailsAsResponseInvalid() {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody("   "));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.querySkillInfo("skill-11111111111111111111111111111111"));

        assertEquals(AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, error.errorCode());
    }

    @Test
    void arrayRootNodeFailsAsResponseInvalid() {
        server.enqueue(json("[{\"id\": \"agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}]"));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals(AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, error.errorCode());
    }

    @Test
    void missingSkillInfoResultCarriesStableErrorCode() {
        server.enqueue(json("{\"resCode\": \"404\", \"resMsg\": \"skill missing\", \"result\": null}"));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.querySkillInfo("skill-11111111111111111111111111111111"));

        assertEquals(AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, error.errorCode());
        assertEquals("MATE_RESPONSE_INVALID", error.stableErrorCode());
    }

    @Test
    void missingRuntimeResultIsRejected() {
        // result 缺失或为 null 不再回退到整个响应体,直接判响应无效。
        server.enqueue(
                json(
                        """
                {
                  "resCode": "403",
                  "resMsg": "agent disabled",
                  "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "name": "Agent A",
                  "bindingSkills": [],
                  "bindingTools": []
                }
                """));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals(AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, error.errorCode());
        assertEquals("GetAgentRuntime result must be an object", error.getMessage());
    }

    @Test
    void nullRuntimeResultIsRejected() {
        server.enqueue(json("{\"resCode\":\"0\",\"result\":null}"));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals(AgentRuntimeErrorCode.MATE_RESPONSE_INVALID, error.errorCode());
    }

    @Test
    void rejectsResponseWhoseContentLengthExceedsLimit() {
        client = newClientWithMaxResponseBytes(32);
        server.enqueue(new MockResponse().setBody("x".repeat(33)));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals("GetAgentRuntime response exceeds campusmate.runtime.max-response-bytes (32)", error.getMessage());
    }

    @Test
    void rejectsChunkedResponseThatExceedsLimit() {
        client = newClientWithMaxResponseBytes(32);
        server.enqueue(new MockResponse().setChunkedBody("x".repeat(33), 8));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals("GetAgentRuntime response exceeds campusmate.runtime.max-response-bytes (32)", error.getMessage());
    }

    @Test
    void rejectsUntypedResourceIdsBeforeSendingRequests() {
        assertThrows(IllegalArgumentException.class, () -> client.getAgentRuntime("agent-a"));
        assertThrows(IllegalArgumentException.class, () -> client.querySkillInfo("skill-1"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void usesConfiguredEndpointPathTemplates() throws Exception {
        var properties = new AgentRuntimeProperties(Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        CampusMateClientProperties campusMateProperties = campusMateProperties(
                "/mate-service/custom%20segment/agents/%s/runtime", "/mate-service/custom%20segment/skills/%s");
        client = new MateServiceClient(properties, campusMateProperties, new ObjectMapper());
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"resCode\":\"0\",\"result\":{}}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"resCode\":\"0\",\"result\":{}}"));

        client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        client.querySkillInfo("skill-11111111111111111111111111111111");

        assertEquals(
                "/mate-service/custom%20segment/agents/agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/runtime",
                server.takeRequest().getPath());
        assertEquals(
                "/mate-service/custom%20segment/skills/skill-11111111111111111111111111111111",
                server.takeRequest().getPath());
    }

    private MateServiceClient newClientWithMaxResponseBytes(int maxResponseBytes) {
        var properties = new AgentRuntimeProperties(
                Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L), maxResponseBytes);
        return new MateServiceClient(properties, campusMateProperties(), new ObjectMapper());
    }

    private CampusMateClientProperties campusMateProperties() {
        return campusMateProperties(AGENT_RUNTIME_PATH_TEMPLATE, SKILL_INFO_QUERY_PATH_TEMPLATE);
    }

    private CampusMateClientProperties campusMateProperties(
            String agentRuntimePathTemplate, String skillInfoPathTemplate) {
        return new CampusMateClientProperties(
                server.url("/").uri(),
                new CampusMateClientProperties.Endpoints(
                        "/mate-service/v1/LLM/chat",
                        "/mate-service/v1/agents/%s",
                        agentRuntimePathTemplate,
                        skillInfoPathTemplate,
                        "/mate-service/v1/runtime/tools/query",
                        "/mate-service/v1/runtime/tools/%s/execute"));
    }
}
