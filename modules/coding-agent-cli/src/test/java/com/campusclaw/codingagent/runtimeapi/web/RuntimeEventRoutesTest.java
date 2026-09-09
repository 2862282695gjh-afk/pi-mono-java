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
import java.util.List;
import java.util.Locale;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.runtimeapi.RuntimeMessageSourceConfiguration;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.event.CommittedEventQueryService;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeSseDispatcher;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeV2EventService;
import com.campusclaw.codingagent.runtimeapi.result.StandaloneResultBeanAdapter;
import com.campusclaw.codingagent.runtimeapi.vo.ListSessionEventsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.SubmitSessionEventRequestVO;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Session Event 提交流与当前分支历史分页 HTTP 契约测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeEventRoutesTest {
    private static final String SESSION_ID = "session-0123456789abcdef0123456789abcdef";

    private RuntimeV2EventService service;

    private CommittedEventQueryService queryService;

    private RuntimeSseDispatcher dispatcher;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeV2EventService.class);
        queryService = mock(CommittedEventQueryService.class);
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
    void shouldStreamDataOnlyEventsWithoutResultBeanWhenPostingV2Message() throws Exception {
        RuntimeEventStream stream = completedStream();
        when(service.submit(
                        eq(SESSION_ID),
                        any(SubmitSessionEventRequestVO.class),
                        eq(Locale.US),
                        eq(MateCredentials.jwt("credential", "opaque-token", "opaque-access-token"))))
                .thenReturn(stream);

        MvcResult initial = mvc.perform(
                        authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content(
                                        "{\"event\":{\"type\":\"user.message\",\"content\":[{\"type\":\"text\",\"text\":\"分析订单\"}]}}"))
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
        assertThat(body).contains("data:", "\"eventId\":\"event_100\"", "\"type\":\"user.message\"");
        assertThat(body).doesNotContain("id:", "event:", "stream.end");
        assertThat(body).doesNotContain("resCode", "resMsg", "result");
        verify(service)
                .submit(
                        eq(SESSION_ID),
                        any(SubmitSessionEventRequestVO.class),
                        eq(Locale.US),
                        eq(MateCredentials.jwt("credential", "opaque-token", "opaque-access-token")));
    }

    @Test
    void shouldRejectUnknownRequestFieldBeforeStartingStream() throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":{\"type\":\"user.interrupt\","
                                + "\"targetEventId\":\"event-root\"},\"modelId\":\"forbidden\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_EVENT_REQUEST"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service, never()).submit(any(), any(), any(Locale.class), any());
    }

    @Test
    void shouldReturnJsonWhenEventIsRejectedBeforeTheFirstSseFrame() throws Exception {
        when(service.submit(any(), any(), any(Locale.class), any()))
                .thenThrow(new RuntimeApiException(RuntimeErrorCode.TOOL_CONFIRMATION_NOT_PENDING));

        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"event\":{\"type\":\"user.tool_confirmation\","
                                + "\"toolCallId\":\"call-1\",\"result\":\"allow\"}}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.resCode").value("TOOL_CONFIRMATION_NOT_PENDING"));
        verify(service).submit(eq(SESSION_ID), any(), eq(Locale.US), any());
    }

    @Test
    void shouldRejectValuesWhoseJsonTypesDoNotMatchTheContract() throws Exception {
        List<String> invalidBodies = List.of(
                "{\"event\":null}",
                "{\"event\":{\"type\":\"user.other\"}}",
                "{\"event\":{\"type\":\"user.interrupt\",\"targetEventId\":1}}",
                "{\"event\":{\"type\":\"user.message\",\"content\":[0]}}",
                "{\"event\":{\"type\":\"user.tool_confirmation\"," + "\"toolCallId\":\"call-1\",\"result\":true}}");

        for (String body : invalidBodies) {
            mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.resCode").value("INVALID_EVENT_REQUEST"));
        }
        verify(service, never()).submit(any(), any(), any(Locale.class), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"steers", "follow-ups", "abort"})
    void shouldReturnNotFoundForRetiredControlRoute(String route) throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{sessionId}/" + route, SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        verify(service, never()).submit(any(), any(), any(Locale.class), any());
    }

    @Test
    void shouldAllowIntegrationHeadersWithoutLocalValidationWhenListingEvents() throws Exception {
        var content = List.<SessionEventResponseVO.UserContentResponseVO>of(
                new SessionEventResponseVO.TextContentResponseVO("text", "hello"));
        var event = new SessionEventResponseVO(
                "event_100",
                "user.message",
                "2026-09-08T01:02:03.456Z",
                new SessionEventResponseVO.UserMessageResponseVO(content));
        when(queryService.list(SESSION_ID, 1, 2L)).thenReturn(new ListSessionEventsResponseVO(List.of(event), 3L));

        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID)
                        .queryParam("limit", "1")
                        .queryParam("page", "2")
                        .header("X-HW-ID", "credential")
                        .header(HttpHeaders.AUTHORIZATION, "not-locally-validated")
                        .header("X-HW-APPKEY", "opaque-appkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resCode").value("0"))
                .andExpect(jsonPath("$.result.events[0].eventId").value("event_100"))
                .andExpect(jsonPath("$.result.events[0].content[0].text").value("hello"))
                .andExpect(jsonPath("$.result.events[0].entrySeq").doesNotExist())
                .andExpect(jsonPath("$.result.nextPage").value(3));
        verify(queryService).list(SESSION_ID, 1, 2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=201", "limit=word", "page=0", "page=word"})
    void shouldUseStableEventListErrorForInvalidNumericPageParameter(String query) throws Exception {
        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}/events?" + query, SESSION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_EVENT_LIST_QUERY"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(queryService, never()).list(any(), any(), any());
    }

    @Test
    void shouldRejectInvalidSessionIdByParameterValidation() throws Exception {
        mvc.perform(get("/campusclaw-service/v1/sessions/{sessionId}/events", "session-old"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_SESSION_ID"))
                .andExpect(jsonPath("$.result").doesNotExist());

        verify(queryService, never()).list(any(), any(), any());
    }

    private static RuntimeEventStream completedStream() {
        RuntimeEventStream stream = new RuntimeEventStream(16, 4096, Duration.ofSeconds(15), event -> 1L);
        stream.emit(RuntimeSseEventVO.dataOnly(
                "user.message", java.util.Map.of("eventId", "event_100", "content", List.of())));
        stream.complete();
        return stream;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header(ClawConstants.Mate.X_HW_ID, "credential")
                .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token")
                .header(ClawConstants.Mate.ACCESS_TOKEN, "opaque-access-token");
    }
}
