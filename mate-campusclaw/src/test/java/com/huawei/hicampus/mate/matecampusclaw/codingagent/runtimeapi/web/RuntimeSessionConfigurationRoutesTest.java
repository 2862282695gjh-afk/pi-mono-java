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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionConfigurationService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;
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
 * Session 模型列表、模型切换与深度思考开关 HTTP 契约测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionConfigurationRoutesTest {
    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeSessionConfigurationService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSessionConfigurationService.class);
        var controller = new RuntimeSessionConfigurationController(service, new StandaloneResultBeanAdapter());
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
    void listModelsReturnsPlainStringArray() throws Exception {
        when(service.listModels(SESSION_ID))
                .thenReturn(new AvailableModelsResponseVO("model-a", List.of("model-a", "model-b")));

        mvc.perform(authenticated(get("/campusclaw-service/v1/sessions/{id}/models", SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.result.current_model_id").value("model-a"))
                .andExpect(jsonPath("$.result.models[0]").value("model-a"))
                .andExpect(jsonPath("$.result.models[0].model_id").doesNotExist());
    }

    @Test
    void changeModelReturnsFullSessionAndNewEtag() throws Exception {
        when(service.changeModel(eq(SESSION_ID), eq("\"snp-old\""), any(ChangeModelRequestVO.class)))
                .thenReturn(view("model-b", false, "\"snp-new\""));

        mvc.perform(authenticated(put("/campusclaw-service/v1/sessions/{id}/model", SESSION_ID))
                        .header(HttpHeaders.IF_MATCH, "\"snp-old\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model_id\":\"model-b\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"snp-new\""))
                .andExpect(jsonPath("$.result.model_id").value("model-b"))
                .andExpect(jsonPath("$.result.thinking").value(false));
    }

    @Test
    void unknownModelFieldIsRejectedBeforeService() throws Exception {
        mvc.perform(authenticated(put("/campusclaw-service/v1/sessions/{id}/model", SESSION_ID))
                        .header(HttpHeaders.IF_MATCH, "\"snp-old\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model_id\":\"model-b\",\"provider\":\"hidden\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_MODEL_REQUEST"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service, never()).changeModel(any(), any(), any());
    }

    @Test
    void thinkingStringCoercionIsRejected() throws Exception {
        mvc.perform(authenticated(put("/campusclaw-service/v1/sessions/{id}/thinking", SESSION_ID))
                        .header(HttpHeaders.IF_MATCH, "\"snp-old\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thinking\":\"true\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_THINKING_REQUEST"));
    }

    @Test
    void missingIfMatchUsesConfirmed428Error() throws Exception {
        when(service.changeModel(eq(SESSION_ID), eq(null), any(ChangeModelRequestVO.class)))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.IF_MATCH_REQUIRED));

        mvc.perform(authenticated(put("/campusclaw-service/v1/sessions/{id}/model", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model_id\":\"model-b\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.resCode").value("IF_MATCH_REQUIRED"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-HW-ID", "credential").header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token");
    }

    private static RuntimeSessionView<GetSessionResponseVO> view(String modelId, boolean thinking, String etag) {
        OffsetDateTime created = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        OffsetDateTime updated = OffsetDateTime.parse("2026-08-18T02:00:00Z");
        var response = new GetSessionResponseVO(
                SESSION_ID, "agent_011CZkYqphY8vELVzwCUpqiQ", modelId, "idle", thinking, created, updated);
        return new RuntimeSessionView<>(response, etag);
    }
}
