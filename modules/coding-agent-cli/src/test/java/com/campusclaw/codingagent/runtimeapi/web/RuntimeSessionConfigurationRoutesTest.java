/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.runtimeapi.auth.RuntimeAuthProperties;
import com.campusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;
import com.campusclaw.codingagent.runtimeapi.auth.StandaloneCredentialVerifier;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionConfigurationService;
import com.campusclaw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.campusclaw.codingagent.runtimeapi.vo.AvailableModelsResponseVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeModelRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.ChangeThinkingRequestVO;
import com.campusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Session 模型列表、模型切换与深度思考开关的精确 HTTP 契约测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionConfigurationRoutesTest {
    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeSessionConfigurationService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSessionConfigurationService.class);
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.setJwtToken("test-jwt");
        properties.setAppKey("test-appkey");
        var authenticator = new RuntimeRequestAuthenticator(new StandaloneCredentialVerifier(properties));
        var controller = new RuntimeSessionConfigurationController(service);
        var routes = new RuntimeApiRoutes()
                .runtimeSessionRoutes(
                        mock(RuntimeSessionController.class),
                        mock(RuntimeEventController.class),
                        controller,
                        new RuntimeErrorFilter(),
                        new RuntimeAuthFilter(authenticator));
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void listModelsReturnsStringArrayWithoutObjectLayer() {
        when(service.listModels(eq(SESSION_ID), any()))
                .thenReturn(new AvailableModelsResponseVO("model-a", List.of("model-a", "model-b")));

        client.get()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/models", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("0")
                .jsonPath("$.result.current_model_id")
                .isEqualTo("model-a")
                .jsonPath("$.result.models[0]")
                .isEqualTo("model-a")
                .jsonPath("$.result.models[1]")
                .isEqualTo("model-b")
                .jsonPath("$.result.models[0].model_id")
                .doesNotExist();
    }

    @Test
    void changeModelReturnsFullSessionAndNewStrongEtag() {
        when(service.changeModel(eq(SESSION_ID), any(), eq("\"snp-old\""), any(ChangeModelRequestVO.class)))
                .thenReturn(view("model-b", false, "\"snp-new\""));

        client.put()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/model", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("X-HW-APPKEY", "test-appkey")
                .header("If-Match", "\"snp-old\"")
                .bodyValue(Map.of("model_id", "model-b"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"snp-new\"")
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.result.session_id")
                .isEqualTo(SESSION_ID)
                .jsonPath("$.result.model_id")
                .isEqualTo("model-b")
                .jsonPath("$.result.thinking")
                .isEqualTo(false)
                .jsonPath("$.result.updated_at")
                .isEqualTo("2026-08-18T02:00:00Z");
    }

    @Test
    void modelRequestRejectsUnknownFieldBeforeService() {
        client.put()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/model", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("If-Match", "\"snp-old\"")
                .bodyValue(Map.of("model_id", "model-b", "provider", "forbidden"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("INVALID_MODEL_REQUEST")
                .jsonPath("$.result")
                .doesNotExist();

        verify(service, never()).changeModel(any(), any(), any(), any());
    }

    @Test
    void thinkingRequestRejectsStringCoercion() {
        client.put()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/thinking", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("If-Match", "\"snp-old\"")
                .bodyValue(Map.of("thinking", "true"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("INVALID_THINKING_REQUEST")
                .jsonPath("$.result")
                .doesNotExist();

        verify(service, never()).changeThinking(any(), any(), any(), any());
    }

    @Test
    void missingIfMatchReturnsConfirmed428Shape() {
        when(service.changeModel(eq(SESSION_ID), any(), isNull(), any(ChangeModelRequestVO.class)))
                .thenThrow(
                        new RuntimeApiException(HttpStatus.PRECONDITION_REQUIRED, RuntimeErrorCode.IF_MATCH_REQUIRED));

        client.put()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/model", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .bodyValue(Map.of("model_id", "model-b"))
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("IF_MATCH_REQUIRED")
                .jsonPath("$.resMsg")
                .isEqualTo("If-Match is required to modify Session configuration.")
                .jsonPath("$.result")
                .doesNotExist();
    }

    @Test
    void thinkingTrueReturnsFullUpdatedSession() {
        when(service.changeThinking(eq(SESSION_ID), any(), eq("\"snp-old\""), any(ChangeThinkingRequestVO.class)))
                .thenReturn(view("model-a", true, "\"snp-thinking\""));

        client.put()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/thinking", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("If-Match", "\"snp-old\"")
                .bodyValue(Map.of("thinking", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"snp-thinking\"")
                .expectBody()
                .jsonPath("$.result.thinking")
                .isEqualTo(true);
    }

    @Test
    void modelManagerFailureAddsRetryAfter() {
        when(service.listModels(eq(SESSION_ID), any()))
                .thenThrow(
                        new RuntimeApiException(HttpStatus.SERVICE_UNAVAILABLE, RuntimeErrorCode.MANAGER_UNAVAILABLE));

        client.get()
                .uri("/campusclaw-service/v1/sessions/{sessionId}/models", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Retry-After", "3")
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("MANAGER_UNAVAILABLE")
                .jsonPath("$.result")
                .doesNotExist();
    }

    private static RuntimeSessionView<GetSessionResponseVO> view(String modelId, boolean thinking, String etag) {
        OffsetDateTime created = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        OffsetDateTime updated = OffsetDateTime.parse("2026-08-18T02:00:00Z");
        var resource = new GetSessionResponseVO(
                SESSION_ID, "agent_011CZkYqphY8vELVzwCUpqiQ", modelId, "idle", thinking, created, updated);
        return new RuntimeSessionView<>(resource, etag);
    }
}
