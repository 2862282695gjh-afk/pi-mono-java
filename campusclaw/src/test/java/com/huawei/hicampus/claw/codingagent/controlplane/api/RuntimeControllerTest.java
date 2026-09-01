/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.controlplane.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.Set;

import com.huawei.hicampus.claw.agent.controlplane.config.ControlPlaneProperties;
import com.huawei.hicampus.claw.agent.controlplane.domain.RuntimeCapability;
import com.huawei.hicampus.claw.agent.controlplane.service.NodeRegistry;
import com.huawei.hicampus.claw.agent.controlplane.service.RuntimeScheduler;
import com.huawei.hicampus.claw.codingagent.controlplane.error.ControlPlaneExceptionHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Runtime 聚合和调度控制面 MVC 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeControllerTest {
    private NodeRegistry registry;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.systemUTC();
        registry = new NodeRegistry(new ControlPlaneProperties(null), clock);
        RuntimeScheduler scheduler = new RuntimeScheduler(registry);
        var objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        mvc = MockMvcBuilders.standaloneSetup(new RuntimeController(registry, scheduler))
                .setControllerAdvice(new ControlPlaneExceptionHandler(clock))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void listsActiveRuntimeAndCapabilityUnion() throws Exception {
        register("10.0.1.1", 9001, Set.of(RuntimeCapability.MODEL_OPENAI, RuntimeCapability.TOOL_BASH));

        mvc.perform(get("/api/v1/runtimes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].host").value("10.0.1.1"))
                .andExpect(jsonPath("$[0].port").value(9001));
        mvc.perform(get("/api/v1/runtimes/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItems("MODEL_OPENAI", "TOOL_BASH")));
    }

    @Test
    void schedulesEligibleAndPreferredNode() throws Exception {
        String preferred = register("10.0.2.1", 9101, Set.of(RuntimeCapability.TOOL_BASH));
        register("10.0.2.2", 9102, Set.of(RuntimeCapability.TOOL_BASH));

        mvc.perform(post("/api/v1/runtimes/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"requiredCapabilities\":[\"TOOL_BASH\"],\"preferredNodeId\":\"" + preferred + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value(preferred))
                .andExpect(jsonPath("$.reason").value("affinity"));
    }

    @Test
    void noEligibleNodeAndInvalidBodyReturnExpectedErrors() throws Exception {
        register("10.0.3.1", 9201, Set.of(RuntimeCapability.TOOL_BASH));

        mvc.perform(post("/api/v1/runtimes/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requiredCapabilities\":[\"MODEL_MISTRAL\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(post("/api/v1/runtimes/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requiredCapabilities\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("requiredCapabilities")));
    }

    private String register(String host, int port, Set<RuntimeCapability> capabilities) {
        return registry.register(host, port, "1.0.0", capabilities).nodeId();
    }
}
