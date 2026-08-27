/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentialHeaders;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Runtime 请求语言范围、权重和回退规则测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/27]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeRequestContextTest {
    @Test
    void qualityWeightsSelectTheHighestPrioritySupportedLocale() {
        assertThat(resolve("zh-CN;q=0.4,en-US;q=0.9")).isEqualTo(Locale.US);
        assertThat(resolve("en-US;q=0.2,zh-CN;q=0.9")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    void languageRangeCanSelectSimplifiedChinese() {
        assertThat(resolve("zh;q=0.8")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    void missingInvalidOrUnsupportedLanguageFallsBackToEnglish() {
        assertThat(RuntimeRequestContext.locale(new MockHttpServletRequest())).isEqualTo(Locale.US);
        assertThat(resolve("not-a-valid-language-range-@")).isEqualTo(Locale.US);
        assertThat(resolve("fr-FR")).isEqualTo(Locale.US);
    }

    @Test
    void capturesCoexistingMateCredentialsWithoutValidation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(MateCredentialHeaders.X_HW_ID, "caller-1");
        request.addHeader(MateCredentialHeaders.X_HW_APPKEY, "app-key-1");
        request.addHeader(MateCredentialHeaders.AUTHORIZATION, "Bearer token-1");
        request.addHeader(MateCredentialHeaders.ACCESS_TOKEN, "access-token-1");

        MateCredentials credentials = RuntimeRequestContext.mateCredentials(request);

        assertThat(credentials)
                .isEqualTo(new MateCredentials("caller-1", "app-key-1", "Bearer token-1", "access-token-1"));
        assertThat(credentials.isComplete()).isTrue();
    }

    @Test
    void missingAccessTokenIsCapturedWithoutLocalRejectionButIsIncompleteForExecution() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(MateCredentialHeaders.X_HW_ID, "caller-1");
        request.addHeader(MateCredentialHeaders.X_HW_APPKEY, "app-key-1");

        MateCredentials credentials = RuntimeRequestContext.mateCredentials(request);

        assertThat(credentials.accessToken()).isNull();
        assertThat(credentials.isComplete()).isFalse();
    }

    private static Locale resolve(String acceptLanguage) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        return RuntimeRequestContext.locale(request);
    }
}
