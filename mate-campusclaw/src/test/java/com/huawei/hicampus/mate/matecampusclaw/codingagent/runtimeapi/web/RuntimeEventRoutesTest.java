/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeAuthProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.StandaloneCredentialVerifier;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event.RuntimeEventService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.EventPageResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.UserEventRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;

/**
 * Session Events POST SSE 与 GET 分页的精确 HTTP 契约测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeEventRoutesTest {
    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeEventService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeEventService.class);
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.setJwtToken("test-jwt");
        properties.setAppKey("test-appkey");
        var authenticator = new RuntimeRequestAuthenticator(new StandaloneCredentialVerifier(properties));
        var eventController = new RuntimeEventController(service);
        var routes = new RuntimeApiRoutes()
                .runtimeSessionRoutes(
                        mock(RuntimeSessionController.class),
                        eventController,
                        new RuntimeErrorFilter(),
                        new RuntimeAuthFilter(authenticator));
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void requestVoAcceptsDeclaredSnakeCaseFields() throws Exception {
        UserEventRequestVO request = new ObjectMapper()
                .readValue(
                        "{\"type\":\"user.message\",\"message\":\"分析订单\",\"file_ids\":[]}", UserEventRequestVO.class);

        assertThat(request.getType()).isEqualTo("user.message");
        assertThat(request.getMessage()).isEqualTo("分析订单");
        assertThat(request.getFileIds()).isEmpty();
    }

    @Test
    void postStreamsNamedEventsWithoutResultBean() {
        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("entry_id", "entry_100");
        user.put("entry_seq", 17L);
        user.put("message", "分析订单");
        user.put("file_ids", List.of());
        user.put("created_at", "2026-08-17T10:00:00Z");
        when(service.submit(eq(SESSION_ID), any(), any(), anyBoolean()))
                .thenReturn(Flux.just(
                        new RuntimeSseEventVO("17", "user.message", user),
                        new RuntimeSseEventVO(null, "session.status.idle", Map.of("status", "idle")),
                        new RuntimeSseEventVO(null, "stream.end", Map.of("reason", "completed"))));

        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .bodyValue(Map.of("type", "user.message", "message", "分析订单", "file_ids", List.of()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith("text/event-stream")
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("id:17", "event:user.message");
                    assertThat(body).contains("\"entry_seq\":17", "event:session.status.idle");
                    assertThat(body).contains("event:stream.end", "\"reason\":\"completed\"");
                    assertThat(body).doesNotContain("resCode", "resMsg", "result");
                });
    }

    @Test
    void postRejectsUnknownRequestFieldBeforeStartingStream() {
        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("Accept-Language", "zh-CN")
                .bodyValue(Map.of("type", "user.message", "message", "分析订单", "model_id", "forbidden"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .valueEquals("Content-Language", "zh-CN")
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("INVALID_EVENT_REQUEST")
                .jsonPath("$.resMsg")
                .isEqualTo("用户事件请求内容不符合约束。")
                .jsonPath("$.result")
                .doesNotExist();

        verify(service, never()).submit(any(), any(), any(), anyBoolean());
    }

    @Test
    void postRejectsMalformedJsonBeforeStartingStream() {
        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/events", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("Content-Type", "application/json")
                .bodyValue("{not-json")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("INVALID_EVENT_REQUEST")
                .jsonPath("$.result")
                .doesNotExist();

        verify(service, never()).submit(any(), any(), any(), anyBoolean());
    }

    @Test
    void getReturnsResultBeanWithEventsAndOpaqueNextPage() {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("type", "user.message");
        event.put("entry_id", "entry_100");
        event.put("entry_seq", 17L);
        event.put("message", "分析订单");
        event.put("file_ids", List.of());
        event.put("created_at", "2026-08-17T10:00:00Z");
        when(service.list(eq(SESSION_ID), any(), eq("1"), eq("page_opaque")))
                .thenReturn(new EventPageResponseVO(List.of(event), "page_next"));

        client.get()
                .uri(uri -> uri.path("/campusclaw-service/v1/sessions/{sessionId}/events")
                        .queryParam("limit", 1)
                        .queryParam("page", "page_opaque")
                        .build(SESSION_ID))
                .header("X-HW-ID", "mate-service")
                .header("X-HW-APPKEY", "test-appkey")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("0")
                .jsonPath("$.result.events[0].type")
                .isEqualTo("user.message")
                .jsonPath("$.result.events[0].entry_seq")
                .isEqualTo(17)
                .jsonPath("$.result.next_page")
                .isEqualTo("page_next");
    }
}
