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

    private MockWebServer server;
    private MateServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        var properties = new AgentRuntimeProperties(
                server.url("/").uri(), Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        client = new MateServiceClient(properties, new ObjectMapper());
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
                          "id": "agent-a",
                          "name": "Agent A",
                          "bindingModels": "gpt-4o",
                          "bindingSkills": {
                            "id": "skill-1",
                            "version": "1.0.0"
                          },
                          "bindingTools": []
                        }
                        """));

        var runtime = client.getAgentRuntime("agent-a");

        assertEquals("agent-a", runtime.id());
        assertEquals(
                List.of("skill-1"),
                runtime.bindingSkills().stream()
                        .map(MateServiceClient.SkillReference::id)
                        .toList());
        assertEquals(
                "/mate-service/v1/agents/agent-a/runtime", server.takeRequest().getPath());
    }

    @Test
    void queriesCompleteSkillInfoAndAcceptsBothVersionFieldNames() throws Exception {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(skillInfoResponse()));

        var skills = client.querySkillInfo("skill-1");

        assertEquals(
                List.of("1.0.0", "2.0.0"),
                skills.stream().map(MateServiceClient.SkillInfo::version).toList());
        assertEquals("calendar", skills.getFirst().bindingTools().getFirst().name());
        assertEquals("base-skill", skills.getFirst().bindingSkills().getFirst().name());
        assertEquals("template body", skills.getFirst().templates().getFirst().content());
        assertEquals("reference body", skills.getFirst().references().getFirst().content());
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/mate-service/v1/skill/query/skill-1", request.getPath());
        assertEquals(0L, request.getBody().size());
    }

    private static String skillInfoResponse() {
        return """
                {"resCode":"0","resMsg":"ok","result":[{
                  "name":"skill-a","id":"skill-1"," version":"1.0.0",
                  "description":"Skill A","useCases":"booking",
                  "bindingTools":[{"id":"tool-1","version":"2.0.0","name":"calendar",
                    "description":"Calendar tool","permission":"allow","source":"local"}],
                  "bindingSkills":[{"id":"skill-0","version":"0.9.0",
                    "name":"base-skill","description":"Base"}],
                  "templates":[{"id":"template-1","name":"request",
                    "content":"template body","fileType":"md"}],
                  "references":[{"id":"reference-1","name":"guide",
                    "content":"reference body","fileType":"txt"}]
                },{
                  "name":"skill-b","id":"skill-2","version":"2.0.0",
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

        AgentRuntimeException error = assertThrows(AgentRuntimeException.class, () -> client.querySkillInfo("skill-1"));

        assertEquals("querySkillInfo failed with resCode 404: skill missing", error.getMessage());
    }

    @Test
    void readsRuntimeEnvelopeWithConfiguredSuccessCode() {
        var properties = new AgentRuntimeProperties(
                server.url("/").uri(), Path.of("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L), "200");
        client = new MateServiceClient(properties, new ObjectMapper());
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                        {
                          "resCode": "200",
                          "resMsg": "ok",
                          "result": {
                            "id": "agent-a",
                            "name": "Agent A",
                            "bindingSkills": [],
                            "bindingTools": []
                          }
                        }
                        """));

        assertEquals("agent-a", client.getAgentRuntime("agent-a").id());
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

        AgentRuntimeException error =
                assertThrows(AgentRuntimeException.class, () -> client.getAgentRuntime("agent-a"));

        assertEquals("GetAgentRuntime failed with resCode 403: agent disabled", error.getMessage());
    }

    @Test
    void postsSkillNamesAndReadsToolList() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                        {
                          "resCode": "0",
                          "resMsg": "ok",
                          "result": [{
                            "skillName": "skill-a",
                            "toolList": [{
                              "toolId": "tool-1",
                              "toolVersion": "1.0.0",
                              "toolName": "calendar",
                              "description": "Calendar tool"
                            }]
                          }]
                        }
                        """));

        var result = client.querySkillTools(List.of("skill-a"));

        assertEquals("calendar", result.getFirst().toolList().getFirst().toolName());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/mate-service/v1/skill/tools/query", request.getPath());
        assertEquals(
                new ObjectMapper().readTree("{\"skillNames\":[\"skill-a\"]}"),
                new ObjectMapper().readTree(request.getBody().readUtf8()));
    }

    @Test
    void rejectsBusinessErrorFromSkillToolQuery() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                """
                        {
                          "resCode": "403",
                          "resMsg": "skill disabled",
                          "result": []
                        }
                        """));

        AgentRuntimeException error =
                assertThrows(AgentRuntimeException.class, () -> client.querySkillTools(List.of("skill-a")));

        assertEquals("querySkillTools failed with resCode 403: skill disabled", error.getMessage());
    }

    @Test
    void rejectsResponseWhoseContentLengthExceedsLimit() {
        client = newClientWithMaxResponseBytes(32);
        server.enqueue(new MockResponse().setBody("x".repeat(33)));

        AgentRuntimeException error =
                assertThrows(AgentRuntimeException.class, () -> client.getAgentRuntime("agent-a"));

        assertEquals("GetAgentRuntime response exceeds campusmate.runtime.max-response-bytes (32)", error.getMessage());
    }

    @Test
    void rejectsChunkedResponseThatExceedsLimit() {
        client = newClientWithMaxResponseBytes(32);
        server.enqueue(new MockResponse().setChunkedBody("x".repeat(33), 8));

        AgentRuntimeException error =
                assertThrows(AgentRuntimeException.class, () -> client.getAgentRuntime("agent-a"));

        assertEquals("GetAgentRuntime response exceeds campusmate.runtime.max-response-bytes (32)", error.getMessage());
    }

    private MateServiceClient newClientWithMaxResponseBytes(int maxResponseBytes) {
        var properties = new AgentRuntimeProperties(
                server.url("/").uri(),
                Path.of("agent"),
                Duration.ofSeconds(1L),
                Duration.ofSeconds(2L),
                "0",
                maxResponseBytes);
        return new MateServiceClient(properties, new ObjectMapper());
    }
}
