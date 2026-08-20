/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Runtime 请求语言范围、权重和回退规则测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/20]
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

    private static Locale resolve(String acceptLanguage) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        return RuntimeRequestContext.locale(request);
    }
}
