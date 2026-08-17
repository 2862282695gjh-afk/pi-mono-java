/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeAuthProperties;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.RuntimeRequestAuthenticator;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.auth.StandaloneCredentialVerifier;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionService;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.session.RuntimeSessionView;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.CreateSessionResponseVO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo.GetSessionResponseVO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 前三个 Runtime Session 接口的精确 HTTP 契约测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeSessionRoutesTest {
    private static final String AGENT_ID = "agent_011CZkYqphY8vELVzwCUpqiQ";

    private static final String SESSION_ID = "01JY8W6M8D9K4H2Q7P3V5N1R0T";

    private RuntimeSessionService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSessionService.class);
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.setJwtToken("test-jwt");
        properties.setAppKey("test-appkey");
        var verifier = new StandaloneCredentialVerifier(properties);
        var authenticator = new RuntimeRequestAuthenticator(verifier);
        var authFilter = new RuntimeAuthFilter(authenticator);
        var errorFilter = new RuntimeErrorFilter();
        var controller = new RuntimeSessionController(service);
        var eventController = mock(RuntimeEventController.class);
        var configurationController = mock(RuntimeSessionConfigurationController.class);
        var routes = new RuntimeApiRoutes()
                .runtimeSessionRoutes(controller, eventController, configurationController, errorFilter, authFilter);
        client = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void createWithJwtReturnsConfirmedResultBeanContract() {
        when(service.create(org.mockito.ArgumentMatchers.eq(AGENT_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(createView("\"snp-create\""));

        client.post()
                .uri("/campusclaw-service/v1/agents/{agentId}/sessions", AGENT_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals("Location", "/campusclaw-service/v1/sessions/" + SESSION_ID)
                .expectHeader()
                .valueEquals("Content-Language", "en-US")
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("0")
                .jsonPath("$.resMsg")
                .isEqualTo("success")
                .jsonPath("$.result.session_id")
                .isEqualTo(SESSION_ID)
                .jsonPath("$.result.agent_id")
                .isEqualTo(AGENT_ID)
                .jsonPath("$.result.thinking")
                .isEqualTo(false)
                .jsonPath("$.result.created_at")
                .isEqualTo("2026-08-18T00:00:00Z")
                .jsonPath("$.result.updated_at")
                .doesNotExist();
    }

    @Test
    void getWithAppKeyReturnsStrongEtagAndNoStore() {
        when(service.get(org.mockito.ArgumentMatchers.eq(SESSION_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(getView("\"snp-zQfM5yN8x2\""));

        client.get()
                .uri("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("X-HW-APPKEY", "test-appkey")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"snp-zQfM5yN8x2\"")
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.result.model_id")
                .isEqualTo("claude-sonnet-4-5")
                .jsonPath("$.result.state")
                .isEqualTo("idle")
                .jsonPath("$.result.created_at")
                .isEqualTo("2026-08-18T00:00:00Z")
                .jsonPath("$.result.updated_at")
                .isEqualTo("2026-08-18T00:00:00Z");
    }

    @Test
    void mixedCredentialsAreRejectedWithoutResultField() {
        client.get()
                .uri("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("X-HW-APPKEY", "test-appkey")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("AUTH_CREDENTIAL_CONFLICT")
                .jsonPath("$.resMsg")
                .isEqualTo("JWT and APPKEY credentials must not be supplied together.")
                .jsonPath("$.result")
                .doesNotExist();
    }

    @Test
    void incompleteCredentialsAreRejected() {
        client.get()
                .uri("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.result")
                .doesNotExist();
    }

    @Test
    void managerUnavailableReturnsRetryAfterWithoutResult() {
        when(service.create(org.mockito.ArgumentMatchers.eq(AGENT_ID), org.mockito.ArgumentMatchers.any()))
                .thenThrow(
                        new RuntimeApiException(HttpStatus.SERVICE_UNAVAILABLE, RuntimeErrorCode.MANAGER_UNAVAILABLE));

        client.post()
                .uri("/campusclaw-service/v1/agents/{agentId}/sessions", AGENT_ID)
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

    @Test
    void invalidSessionIdUsesChineseErrorWhenRequested() {
        client.get()
                .uri("/campusclaw-service/v1/sessions/bad%20id")
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .header("Accept-Language", "zh-CN")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .valueEquals("Content-Language", "zh-CN")
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("INVALID_SESSION_ID")
                .jsonPath("$.resMsg")
                .isEqualTo("session_id 格式不正确。")
                .jsonPath("$.result")
                .doesNotExist();
    }

    @Test
    void sessionNotFoundUsesConfirmedErrorShape() {
        when(service.get(org.mockito.ArgumentMatchers.eq(SESSION_ID), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeApiException(HttpStatus.NOT_FOUND, RuntimeErrorCode.SESSION_NOT_FOUND));

        client.get()
                .uri("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.resCode")
                .isEqualTo("SESSION_NOT_FOUND")
                .jsonPath("$.result")
                .doesNotExist();
    }

    @Test
    void deleteReturnsEmpty204() {
        doNothing()
                .when(service)
                .delete(org.mockito.ArgumentMatchers.eq(SESSION_ID), org.mockito.ArgumentMatchers.any());

        client.delete()
                .uri("/campusclaw-service/v1/sessions/{sessionId}", SESSION_ID)
                .header("X-HW-ID", "mate-service")
                .header("Authorization", "Bearer test-jwt")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();
    }

    private static RuntimeSessionView<CreateSessionResponseVO> createView(String etag) {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        var resource = new CreateSessionResponseVO(SESSION_ID, AGENT_ID, "claude-sonnet-4-5", "idle", false, time);
        return new RuntimeSessionView<>(resource, etag);
    }

    private static RuntimeSessionView<GetSessionResponseVO> getView(String etag) {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-18T00:00:00Z");
        var resource = new GetSessionResponseVO(SESSION_ID, AGENT_ID, "claude-sonnet-4-5", "idle", false, time, time);
        return new RuntimeSessionView<>(resource, etag);
    }
}
