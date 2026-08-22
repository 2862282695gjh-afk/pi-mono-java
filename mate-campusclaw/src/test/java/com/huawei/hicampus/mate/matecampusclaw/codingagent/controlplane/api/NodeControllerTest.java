/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.config.ControlPlaneProperties;
import com.huawei.hicampus.mate.matecampusclaw.agent.controlplane.service.NodeRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.controlplane.error.ControlPlaneExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 节点控制面 MVC 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class NodeControllerTest {
    private NodeRegistry registry;

    private MockMvc mvc;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.systemUTC();
        registry = new NodeRegistry(new ControlPlaneProperties(null), clock);
        objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        mvc = MockMvcBuilders.standaloneSetup(new NodeController(registry))
                .setControllerAdvice(new ControlPlaneExceptionHandler(clock))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void registerListHeartbeatAndDeregisterNode() throws Exception {
        String nodeId = registerNode();

        mvc.perform(get("/api/v1/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value(nodeId));
        mvc.perform(post("/api/v1/nodes/{id}/heartbeat", nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeAgents\":2,\"queuedTasks\":1,\"cpuLoad\":0.7,\"memoryUsedMb\":512}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.activeAgents").value(2));
        mvc.perform(delete("/api/v1/nodes/{id}", nodeId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/nodes/{id}", nodeId)).andExpect(status().isNotFound());
    }

    @Test
    void missingNodeAndInvalidBodyReturnStructuredErrors() throws Exception {
        mvc.perform(get("/api/v1/nodes/node-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("node-missing")));
        mvc.perform(post("/api/v1/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"10.0.0.1\",\"port\":0,\"version\":\"1.0\",\"capabilities\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("port")));
    }

    @Test
    void missingBodyReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/nodes").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("request body is required"));
    }

    private String registerNode() throws Exception {
        String body = mvc.perform(post("/api/v1/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"10.0.0.10\",\"port\":9001,\"version\":\"1.0.0\","
                                + "\"capabilities\":[\"MODEL_OPENAI\",\"TOOL_BASH\"]}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String nodeId = objectMapper.readTree(body).path("nodeId").asText();
        assertThat(nodeId).startsWith("node-");
        return nodeId;
    }
}
