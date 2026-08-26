/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventQueryService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeSseDispatcher;
import com.campusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.vo.EventPageResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Session Event 提交流与当前分支历史分页 HTTP 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventRoutesTest {
    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private RuntimeEventService service;

    private RuntimeEventQueryService queryService;

    private RuntimeSseDispatcher dispatcher;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeEventService.class);
        queryService = mock(RuntimeEventQueryService.class);
        dispatcher = new RuntimeSseDispatcher();
        var controller =
                new RuntimeEventController(service, queryService, new StandaloneResultBeanAdapter(), dispatcher);
        var messages = new RuntimeMessageSourceConfiguration().messageSource();
        var objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RuntimeExceptionHandler(messages))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @AfterEach
    void closeDispatcher() {
        dispatcher.close();
    }

    @Test
    void postStreamsNamedEventsWithoutResultBean() throws Exception {
        RuntimeEventStream stream = completedStream();
        when(service.submit(
                        eq(SESSION_ID),
                        any(UserEventRequestVO.class),
                        eq(Locale.US),
                        eq(MateCredentials.jwt("credential", "opaque-token"))))
                .thenReturn(stream);

        MvcResult initial = mvc.perform(
                        authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"分析订单\",\"fileIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        initial.getAsyncResult(1_000L);

        String body = mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("id:17", "event:user.message", "event:stream.end");
        assertThat(body).doesNotContain("resCode", "resMsg", "result");
        verify(service)
                .submit(
                        eq(SESSION_ID),
                        any(UserEventRequestVO.class),
                        eq(Locale.US),
                        eq(MateCredentials.jwt("credential", "opaque-token")));
    }

    @Test
    void postRejectsUnknownRequestFieldBeforeStartingStream() throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"分析订单\",\"modelId\":\"forbidden\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_EVENT_REQUEST"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service, never()).submit(any(), any(), any(Locale.class), any());
    }

    @Test
    void postRejectsValuesWhoseJsonTypesDoNotMatchTheContract() throws Exception {
        List<String> invalidBodies = List.of(
                "{\"type\":\"user.message\",\"message\":\"分析订单\"}",
                "{\"message\":1}",
                "{\"fileIds\":[1]}",
                "{\"fileIds\":\"file_1\"}",
                "{\"file_ids\":[]}");

        for (String body : invalidBodies) {
            mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resCode").value("INVALID_EVENT_REQUEST"));
        }
        verify(service, never()).submit(any(), any(), any(Locale.class), any());
    }

    @Test
    void getAllowsIntegrationHeadersToCoexistWithoutLocalValidation() throws Exception {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("type", "user.message");
        event.put("entryId", "entry_100");
        event.put("entrySeq", 17L);
        when(queryService.list(SESSION_ID, "1", "page_opaque", Locale.US))
                .thenReturn(new EventPageResponseVO(List.of(event), "page_next"));

        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID)
                        .queryParam("limit", "1")
                        .queryParam("page", "page_opaque")
                        .header("X-HW-ID", "credential")
                        .header(HttpHeaders.AUTHORIZATION, "not-locally-validated")
                        .header("X-HW-APPKEY", "opaque-appkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resCode").value("0"))
                .andExpect(jsonPath("$.result.events[0].entrySeq").value(17))
                .andExpect(jsonPath("$.result.nextPage").value("page_next"));
    }

    @Test
    void invalidSessionIdIsRejectedByParameterValidation() throws Exception {
        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}/events", "session-old"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_SESSION_ID"))
                .andExpect(jsonPath("$.result").doesNotExist());

        verify(queryService, never()).list(any(), any(), any(), any(Locale.class));
    }

    private static RuntimeEventStream completedStream() {
        RuntimeEventStream stream = new RuntimeEventStream(16, 4096, Duration.ofSeconds(15), event -> 1L);
        stream.emit(
                new RuntimeSseEventVO("17", "user.message", java.util.Map.of("entryId", "entry_100", "entrySeq", 17L)));
        stream.emit(new RuntimeSseEventVO(null, "stream.end", java.util.Map.of("reason", "completed")));
        stream.complete();
        return stream;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-HW-ID", "credential").header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token");
    }
}
