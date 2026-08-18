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

import com.campusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;
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
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Session Event 提交流与当前分支历史分页 HTTP 契约测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeEventRoutesTest {
    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeEventService service;

    private RuntimeSseDispatcher dispatcher;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeEventService.class);
        dispatcher = new RuntimeSseDispatcher();
        var controller = new RuntimeEventController(
                service, new StandaloneResultBeanAdapter(), dispatcher);
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

    @AfterEach
    void closeDispatcher() {
        dispatcher.close();
    }

    @Test
    void postStreamsNamedEventsWithoutResultBean() throws Exception {
        RuntimeEventStream stream = completedStream();
        when(service.submit(eq(SESSION_ID), any(UserEventRequestVO.class), eq(false))).thenReturn(stream);

        MvcResult initial = mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{id}/events", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"user.message\",\"message\":\"分析订单\",\"file_ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("id:17", "event:user.message", "event:stream.end");
        assertThat(body).doesNotContain("resCode", "resMsg", "result");
    }

    @Test
    void postRejectsUnknownRequestFieldBeforeStartingStream() throws Exception {
        mvc.perform(authenticated(post("/campusclaw-service/v1/sessions/{id}/events", SESSION_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"user.message\",\"message\":\"分析订单\",\"model_id\":\"forbidden\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resCode").value("INVALID_EVENT_REQUEST"))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(service, never()).submit(any(), any(), eq(false));
    }

    @Test
    void getReturnsResultBeanWithOpaqueNextPage() throws Exception {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("type", "user.message");
        event.put("entry_id", "entry_100");
        event.put("entry_seq", 17L);
        when(service.list(SESSION_ID, "1", "page_opaque"))
                .thenReturn(new EventPageResponseVO(List.of(event), "page_next"));

        mvc.perform(get("/campusclaw-service/v1/sessions/{id}/events", SESSION_ID)
                        .queryParam("limit", "1")
                        .queryParam("page", "page_opaque")
                        .header("X-HW-ID", "credential")
                        .header("X-HW-APPKEY", "opaque-appkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resCode").value("0"))
                .andExpect(jsonPath("$.result.events[0].entry_seq").value(17))
                .andExpect(jsonPath("$.result.next_page").value("page_next"));
    }

    private static RuntimeEventStream completedStream() {
        RuntimeEventStream stream = new RuntimeEventStream(
                16, 4096, Duration.ofSeconds(15), event -> 1L);
        stream.emit(new RuntimeSseEventVO(
                "17", "user.message", java.util.Map.of("entry_id", "entry_100", "entry_seq", 17L)));
        stream.emit(new RuntimeSseEventVO(null, "stream.end", java.util.Map.of("reason", "completed")));
        stream.complete();
        return stream;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-HW-ID", "credential")
                .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token");
    }
}
