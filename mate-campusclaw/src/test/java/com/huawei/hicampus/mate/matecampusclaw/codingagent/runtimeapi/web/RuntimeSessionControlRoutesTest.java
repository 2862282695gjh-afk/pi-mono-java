/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeAuthProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.StandaloneCredentialVerifier;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionControlService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ControlMessageAcceptedResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.ControlMessageRequestVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Session Steer、FollowUp 与 Abort 的精确 HTTP 契约测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionControlRoutesTest {
    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeSessionControlService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSessionControlService.class);
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.setJwtToken("test-jwt");
        properties.setAppKey("test-appkey");
        var authenticator = new RuntimeRequestAuthenticator(new StandaloneCredentialVerifier(properties));
        var routes = new RuntimeApiRoutes()
                .runtimeSessionRoutes(
                        mock(RuntimeSessionController.class),
                        mock(RuntimeEventController.class),
                        mock(RuntimeSessionConfigurationController.class),
                        new RuntimeSessionControlController(service),
                        new RuntimeErrorFilter(),
                        new RuntimeAuthFilter(authenticator));
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void requestVoAcceptsOnlyStringMessage() throws Exception {
        ControlMessageRequestVO request =
                new ObjectMapper().readValue("{\"message\":\"先只分析异常订单\"}", ControlMessageRequestVO.class);

        assertThat(request.getMessage()).isEqualTo("先只分析异常订单");
    }

    @Test
    void steerReturnsAcceptedCompanyEnvelope() {
        when(service.steer(eq(SESSION_ID), any(), any()))
                .thenReturn(
                        new ControlMessageAcceptedResponseVO(SESSION_ID, OffsetDateTime.parse("2026-08-17T15:10:00Z")));

        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/steers", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .bodyValue(Map.of("message", "先只分析异常订单"))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectHeader()
                .valueEquals("Content-Language", "en-US")
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("0")
                .jsonPath("$.result.session_id")
                .isEqualTo(SESSION_ID)
                .jsonPath("$.result.accepted_at")
                .isEqualTo("2026-08-17T15:10:00Z");
    }

    @Test
    void followUpSupportsCompatibleAppKeyAuthentication() {
        when(service.followUp(eq(SESSION_ID), any(), any()))
                .thenReturn(
                        new ControlMessageAcceptedResponseVO(SESSION_ID, OffsetDateTime.parse("2026-08-17T15:11:00Z")));

        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/follow-ups", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("X-HW-APPKEY", "test-appkey")
                .bodyValue(Map.of("message", "完成后再给出摘要"))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.result.accepted_at")
                .isEqualTo("2026-08-17T15:11:00Z");
    }

    @Test
    void malformedSteerBodyUsesEndpointSpecificError() {
        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/steers", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("Accept-Language", "zh-CN")
                .bodyValue(Map.of("message", "继续", "file_ids", java.util.List.of()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("INVALID_STEER_REQUEST")
                .jsonPath("$.resMsg")
                .isEqualTo("Steering Message 请求不符合约束。")
                .jsonPath("$.result")
                .doesNotExist();

        verify(service, never()).steer(any(), any(), any());
    }

    @Test
    void malformedFollowUpBodyUsesEndpointSpecificError() {
        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/follow-ups", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .bodyValue(Map.of("message", 7))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("INVALID_FOLLOW_UP_REQUEST")
                .jsonPath("$.result")
                .doesNotExist();

        verify(service, never()).followUp(any(), any(), any());
    }

    @Test
    void abortReturnsNoContentWithoutResultBean() {
        client.post()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/abort", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .isEmpty();

        verify(service).abort(eq(SESSION_ID), any());
    }
}
