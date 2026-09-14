/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionConfigurationService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionUpdateService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.campusclaw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.LifetimeCostResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.LifetimeUsageResponseVO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Session 模型列表、模型切换与深度思考开关 HTTP 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeSessionConfigurationRoutesTest {
    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private RuntimeSessionConfigurationService service;

    private RuntimeSessionUpdateService updateService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSessionConfigurationService.class);
        updateService = mock(RuntimeSessionUpdateService.class);
        var adapter = new StandaloneResultBeanAdapter();
        var configurationController = new RuntimeSessionConfigurationController(service, adapter);
        var sessionController = new RuntimeSessionController(mock(RuntimeSessionService.class), adapter, updateService);
        var messages = new RuntimeMessageSourceConfiguration().messageSource();
        var objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                .build();
        mvc = MockMvcBuilders.standaloneSetup(configurationController, sessionController)
                .setControllerAdvice(new RuntimeExceptionHandler(messages))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void listModelsReturnsPlainStringArray() throws Exception {
        when(service.listModels(SESSION_ID))
                .thenReturn(new AvailableModelsResponseVO("model-a", List.of("model-a", "model-b")));

        mvc.perform(authenticated(get("/campusclaw-service/v1/sessions/{sessionId}/models", SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.result.currentModelId").value("model-a"))
                .andExpect(jsonPath("$.result.models[0]").value("model-a"))
                .andExpect(jsonPath("$.result.models[0].modelId").doesNotExist());
    }

    @Test
    void changeModelReturnsFullSessionAndNewEtag() throws Exception {
        when(updateService.update(eq(SESSION_ID), eq("\"snp-old\""), any()))
                .thenReturn(view("model-b", false, "\"snp-new\""));

        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID))
                        .header(HttpHeaders.IF_MATCH, "\"snp-old\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"model-b\",\"thinking\":false,\"mode\":\"plan\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"snp-new\""))
                .andExpect(jsonPath("$.result.modelId").value("model-b"))
                .andExpect(jsonPath("$.result.thinking").value(false));
    }

    @Test
    void snakeCaseModelFieldIsRejectedBeforeService() throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID))
                        .header(HttpHeaders.IF_MATCH, "\"snp-old\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model_id\":\"model-b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_SESSION_UPDATE_REQUEST"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(updateService, never()).update(any(), any(), any());
    }

    @Test
    void thinkingStringCoercionIsRejected() throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID))
                        .header(HttpHeaders.IF_MATCH, "\"snp-old\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thinking\":\"true\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_SESSION_UPDATE_REQUEST"));
    }

    @Test
    void invalidSessionIdIsRejectedByParameterValidation() throws Exception {
        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}/models", "session-old"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_SESSION_ID"))
                .andExpect(jsonPath("$.result").doesNotExist());

        verify(service, never()).listModels(anyString());
    }

    @Test
    void missingIfMatchUsesConfirmed428Error() throws Exception {
        when(updateService.update(eq(SESSION_ID), eq(null), any()))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.IF_MATCH_REQUIRED));

        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"model-b\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.resCode").value("IF_MATCH_REQUIRED"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void ignoredAndDuplicatePropertiesAreRejectedBeforeService() throws Exception {
        for (String body : List.of(
                "{\"mode\":\"build\",\"modeSubmitted\":true}",
                "{\"displayName\":\"x\",\"nonEmptyUpdate\":true}",
                "{\"mode\":\"build\",\"mode\":\"plan\"}")) {
            mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID))
                            .header(HttpHeaders.IF_MATCH, "\"snp-old\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resCode").value("INVALID_SESSION_UPDATE_REQUEST"));
        }
        verify(updateService, never()).update(any(), any(), any());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-HW-ID", "credential").header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token");
    }

    private static RuntimeSessionView<GetSessionResponseVO> view(String modelId, boolean thinking, String etag) {
        OffsetDateTime created = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        OffsetDateTime updated = OffsetDateTime.parse("2026-08-18T02:00:00Z");
        var response = new GetSessionResponseVO(
                SESSION_ID,
                "agent-0123456789abcdef0123456789abcdef",
                null,
                modelId,
                "idle",
                thinking,
                new LifetimeUsageResponseVO(
                        0L,
                        0L,
                        0L,
                        0L,
                        0L,
                        new LifetimeCostResponseVO(
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
                created,
                updated);
        return new RuntimeSessionView<>(response, etag);
    }
}
