/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionControlService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ControlMessageAcceptedResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Session Steer、FollowUp 与 Abort 的精确 HTTP 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionControlRoutesTest {
    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeSessionControlService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSessionControlService.class);
        var controller = new RuntimeSessionControlController(service, new StandaloneResultBeanAdapter());
        var interceptor = new RuntimeAuthenticationInterceptor(new RuntimeRequestAuthenticator());
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        var objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .setControllerAdvice(new RuntimeExceptionHandler(messages))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void steerReturnsAcceptedCompanyEnvelope() throws Exception {
        when(service.steer(eq(SESSION_ID), any(ControlMessageRequestVO.class)))
                .thenReturn(acceptedAt("2026-08-17T15:10:00Z"));

        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{id}/steers", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"先只分析异常订单\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en-US"))
                .andExpect(jsonPath("$.resCode").value("0"))
                .andExpect(jsonPath("$.result.session_id").value(SESSION_ID))
                .andExpect(jsonPath("$.result.accepted_at").value("2026-08-17T15:10:00Z"));
    }

    @Test
    void followUpSupportsCompatibleAppKeyAuthentication() throws Exception {
        when(service.followUp(eq(SESSION_ID), any(ControlMessageRequestVO.class)))
                .thenReturn(acceptedAt("2026-08-17T15:11:00Z"));

        mvc.perform(post("/campusclaw-service/v1/sessions/{id}/follow-ups", SESSION_ID)
                        .header("X-HW-ID", "credential")
                        .header("X-HW-APPKEY", "opaque-appkey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"完成后再给出摘要\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result.accepted_at").value("2026-08-17T15:11:00Z"));
    }

    @Test
    void malformedBodyUsesEndpointSpecificErrorWithoutResult() throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{id}/steers", SESSION_ID))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"继续\",\"file_ids\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_STEER_REQUEST"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service, never()).steer(any(), any());
    }

    @Test
    void nonLocalExecutionReturnsStableRetryableError() throws Exception {
        when(service.steer(eq(SESSION_ID), any(ControlMessageRequestVO.class)))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.SESSION_EXECUTION_UNAVAILABLE));

        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{id}/steers", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"继续\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
                .andExpect(jsonPath("$.resCode").value("SESSION_EXECUTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void abortReturnsNoContentWithoutResultBean() throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{id}/abort", SESSION_ID)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));
        verify(service).abort(SESSION_ID);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-HW-ID", "credential").header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token");
    }

    private static ControlMessageAcceptedResponseVO acceptedAt(String value) {
        return new ControlMessageAcceptedResponseVO(SESSION_ID, OffsetDateTime.parse(value));
    }
}
