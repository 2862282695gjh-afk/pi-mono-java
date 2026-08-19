/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * JWT 与 APPKEY 互斥请求头形状校验测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeRequestAuthenticatorTest {
    private final RuntimeRequestAuthenticator authenticator = new RuntimeRequestAuthenticator();

    @Test
    void acceptsJwtHeaderPairWithoutInterpretingCredentialIdentityAsOwner() {
        MockHttpServletRequest request = request("credential-id");
        request.addHeader("Authorization", "bearer opaque-token");

        CallerAuthContext context = authenticator.authenticate(request);

        assertThat(context.credentialId()).isEqualTo("credential-id");
        assertThat(context.credentialMode()).isEqualTo(CredentialMode.JWT);
    }

    @Test
    void acceptsAppKeyHeaderPair() {
        MockHttpServletRequest request = request("credential-id");
        request.addHeader("X-HW-APPKEY", "opaque-appkey");

        assertThat(authenticator.authenticate(request).credentialMode()).isEqualTo(CredentialMode.APPKEY);
    }

    @Test
    void rejectsMixedCredentialModes() {
        MockHttpServletRequest request = request("credential-id");
        request.addHeader("Authorization", "Bearer opaque-token");
        request.addHeader("X-HW-APPKEY", "opaque-appkey");

        assertError(request, RuntimeErrorCode.AUTH_CREDENTIAL_CONFLICT);
    }

    @Test
    void rejectsIncompleteBlankAndDuplicateHeaders() {
        assertError(request("credential-id"), RuntimeErrorCode.UNAUTHENTICATED);
        MockHttpServletRequest blankToken = request("credential-id");
        blankToken.addHeader("Authorization", "Bearer   ");
        assertError(blankToken, RuntimeErrorCode.UNAUTHENTICATED);
        MockHttpServletRequest duplicate = request("credential-id");
        duplicate.addHeader("Authorization", "Bearer first");
        duplicate.addHeader("Authorization", "Bearer second");
        assertError(duplicate, RuntimeErrorCode.UNAUTHENTICATED);
    }

    private static MockHttpServletRequest request(String credentialId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-HW-ID", credentialId);
        return request;
    }

    private void assertError(MockHttpServletRequest request, RuntimeErrorCode expected) {
        assertThatThrownBy(() -> authenticator.authenticate(request))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(expected);
                    assertThat(error.status().value()).isEqualTo(401);
                });
    }
}
