/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
        var properties = new AgentRuntimeProperties(
                server.url("/").uri(), Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        client = new MateServiceClient(
                properties, new ObjectMapper(), AGENT_RUNTIME_PATH_TEMPLATE, SKILL_INFO_QUERY_PATH_TEMPLATE);
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
                        {
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingModels": "gpt-4o",
                          "bindingSkills": {
                            "id": "skill-11111111111111111111111111111111",
                            "version": "1.0.0"
                          },
                          "bindingTools": []
                        }
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
                        {
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingModels": ["glm-5.2", "minimax-m2.5"],
                          "description": ["Diagnoses device faults", "Drafts reports"]
                        }
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
                        {
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingModels": "glm-5.2",
                          "description": "Diagnoses device faults"
                        }
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
                        {
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
                        }
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
                        {
                          "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "name": "Agent A",
                          "bindingAgents": {
                            "id": "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            "name": "field-ops",
                            "description": "Handles on-site device operations"
                          }
                        }
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
    void queriesCompleteSkillInfoAndAcceptsBothVersionFieldNames() throws Exception {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(skillInfoResponse()));

        var skills = client.querySkillInfo("skill-11111111111111111111111111111111");

        assertEquals(
                List.of("1.0.0", "2.0.0"),
                skills.stream().map(MateServiceClient.SkillInfo::version).toList());
        assertEquals("calendar", skills.getFirst().bindingTools().getFirst().name());
        assertEquals("base-skill", skills.getFirst().bindingSkills().getFirst().name());
        assertEquals("template body", skills.getFirst().templates().getFirst().content());
        assertEquals("reference body", skills.getFirst().references().getFirst().content());
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/mate-service/v1/skill/query/skill-11111111111111111111111111111111", request.getPath());
        assertEquals(0L, request.getBody().size());
    }

    private static String skillInfoResponse() {
        return """
                {"resCode":"0","resMsg":"ok","result":[{
                  "name":"skill-a","id":"skill-11111111111111111111111111111111"," version":"1.0.0",
                  "description":"Skill A","useCases":"booking",
                  "bindingTools":[{"id":"tool-11111111111111111111111111111111","version":"2.0.0","name":"calendar",
                    "description":"Calendar tool","permission":"allow","source":"local"}],
                  "bindingSkills":[{"id":"skill-00000000000000000000000000000000","version":"0.9.0",
                    "name":"base-skill","description":"Base"}],
                  "templates":[{"id":"template-1","name":"request",
                    "content":"template body","fileType":"md"}],
                  "references":[{"id":"reference-1","name":"guide",
                    "content":"reference body","fileType":"txt"}]
                },{
                  "name":"skill-b","id":"skill-22222222222222222222222222222222","version":"2.0.0",
                  "description":"Skill B","useCases":"reporting","bindingTools":[],
                  "bindingSkills":[],"templates":[],"references":[]
                }]}
                """;
    }

    @Test
    void rejectsBusinessErrorFromSkillInfoQuery() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                        {
                          "resCode": "404",
                          "resMsg": "skill missing",
                          "result": []
                        }
                        """));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.querySkillInfo("skill-11111111111111111111111111111111"));

        assertEquals("querySkillInfo failed with resCode 404: skill missing", error.getMessage());
    }

    @Test
    void readsRuntimeEnvelopeWithConfiguredSuccessCode() {
        var properties = new AgentRuntimeProperties(
                server.url("/").uri(), Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L), "200");
        client = new MateServiceClient(
                properties, new ObjectMapper(), AGENT_RUNTIME_PATH_TEMPLATE, SKILL_INFO_QUERY_PATH_TEMPLATE);
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                        {
                          "resCode": "200",
                          "resMsg": "ok",
                          "result": {
                            "id": "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "name": "Agent A",
                            "bindingSkills": [],
                            "bindingTools": []
                          }
                        }
                        """));

        assertEquals(
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").id());
    }

    @Test
    void rejectsBusinessErrorFromRuntimeQuery() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                        {
                          "resCode": "403",
                          "resMsg": "agent disabled",
                          "result": null
                        }
                        """));

        AgentRuntimeException error = assertThrows(
                AgentRuntimeException.class, () -> client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals("GetAgentRuntime failed with resCode 403: agent disabled", error.getMessage());
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
        var properties = new AgentRuntimeProperties(
                server.url("/").uri(), Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        client =
                new MateServiceClient(properties, new ObjectMapper(), "/custom/agents/%s/runtime", "/custom/skills/%s");
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"resCode\":\"0\",\"result\":[]}"));

        client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        client.querySkillInfo("skill-11111111111111111111111111111111");

        assertEquals(
                "/custom/agents/agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/runtime",
                server.takeRequest().getPath());
        assertEquals(
                "/custom/skills/skill-11111111111111111111111111111111",
                server.takeRequest().getPath());
    }

    private MateServiceClient newClientWithMaxResponseBytes(int maxResponseBytes) {
        var properties = new AgentRuntimeProperties(
                server.url("/").uri(),
                Path.of("agent"),
                Duration.ofSeconds(1L),
                Duration.ofSeconds(2L),
                "0",
                maxResponseBytes);
        return new MateServiceClient(
                properties, new ObjectMapper(), AGENT_RUNTIME_PATH_TEMPLATE, SKILL_INFO_QUERY_PATH_TEMPLATE);
    }
}
