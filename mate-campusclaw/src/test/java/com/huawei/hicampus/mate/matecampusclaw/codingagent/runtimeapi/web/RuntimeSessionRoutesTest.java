/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 已确认的创建、读取和删除 Session HTTP 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionRoutesTest {
    private static final String AGENT_ID = "agent-0123456789abcdef0123456789abcdef";

    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private RuntimeSessionService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSessionService.class);
        var controller = new RuntimeSessionController(service, new StandaloneResultBeanAdapter());
        var messages = new RuntimeMessageSourceConfiguration().messageSource();
        var objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RuntimeExceptionHandler(messages))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void createWithJwtReturnsConfirmedResultBean() throws Exception {
        when(service.create(AGENT_ID)).thenReturn(createView());

        mvc.perform(post("/campusclaw-service/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("X-HW-ID", "credential-jwt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/campusclaw-service/v1/sessions/" + SESSION_ID))
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en-US"))
                .andExpect(jsonPath("$.resCode").value("0"))
                .andExpect(jsonPath("$.resMsg").value("success"))
                .andExpect(jsonPath("$.result.session_id").value(SESSION_ID))
                .andExpect(jsonPath("$.result.agent_id").value(AGENT_ID))
                .andExpect(jsonPath("$.result.thinking").value(true))
                .andExpect(jsonPath("$.result.updated_at").doesNotExist());
    }

    @Test
    void getWithAppKeyReturnsEtagAndNoStore() throws Exception {
        when(service.get(SESSION_ID)).thenReturn(getView());

        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID)
                        .header("X-HW-ID", "credential-appkey")
                        .header("X-HW-APPKEY", "opaque-appkey"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"snp-resource\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.result.model_id").value("model-default"))
                .andExpect(jsonPath("$.result.updated_at").value("2026-08-18T00:00:00Z"));
    }

    @Test
    void deleteReturnsEmptyNoContent() throws Exception {
        doNothing().when(service).delete(eq(SESSION_ID));

        mvc.perform(delete("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));
    }

    @Test
    void mixedCredentialsAreNotValidatedInsideRuntime() throws Exception {
        when(service.get(SESSION_ID)).thenReturn(getView());

        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID)
                        .header("X-HW-ID", "credential")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token")
                        .header("X-HW-APPKEY", "opaque-appkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.session_id").value(SESSION_ID));
    }

    @Test
    void managerUnavailableAddsRetryAfterAndLocalizesError() throws Exception {
        when(service.create(AGENT_ID)).thenThrow(new RuntimeApiException(RuntimeErrorCode.MANAGER_UNAVAILABLE));

        mvc.perform(post("/campusclaw-service/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("X-HW-ID", "credential")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "zh-CN"))
                .andExpect(jsonPath("$.resCode").value("MANAGER_UNAVAILABLE"))
                .andExpect(jsonPath("$.resMsg").value("Model Manager 暂时不可用，请稍后重试。"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    private static RuntimeSessionView<CreateSessionResponseVO> createView() {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        var response = new CreateSessionResponseVO(SESSION_ID, AGENT_ID, "model-default", "idle", true, time);
        return new RuntimeSessionView<>(response, "\"snp-create\"");
    }

    private static RuntimeSessionView<GetSessionResponseVO> getView() {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        var response = new GetSessionResponseVO(SESSION_ID, AGENT_ID, "model-default", "idle", false, time, time);
        return new RuntimeSessionView<>(response, "\"snp-resource\"");
    }
}
