/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Runtime 错误码 HTTP 语义和中英文消息目录完整性测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeErrorCodeTest {
    @Test
    void everyErrorCodeHasEnglishAndChineseMessages() {
        ResourceBundle english = ResourceBundle.getBundle("i18n/messages", Locale.US);
        ResourceBundle chinese = ResourceBundle.getBundle("i18n/messages", Locale.SIMPLIFIED_CHINESE);
        Set<String> expectedKeys = Arrays.stream(RuntimeErrorCode.values())
                .map(RuntimeErrorCode::messageKey)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(english.keySet()).isEqualTo(expectedKeys);
        assertThat(chinese.keySet()).isEqualTo(expectedKeys);

        for (RuntimeErrorCode code : RuntimeErrorCode.values()) {
            assertThat(english.getString(code.messageKey())).isNotBlank();
            assertThat(chinese.getString(code.messageKey())).isNotBlank();
        }
    }

    @Test
    void retryableErrorsCarryExplicitRetryDelay() {
        assertThat(RuntimeErrorCode.MANAGER_UNAVAILABLE.retryAfterSeconds()).hasValue(3);
        assertThat(RuntimeErrorCode.RUNTIME_CAPACITY_EXCEEDED.retryAfterSeconds())
                .hasValue(3);
        assertThat(RuntimeErrorCode.SESSION_EXECUTION_UNAVAILABLE.retryAfterSeconds())
                .hasValue(3);
        assertThat(RuntimeErrorCode.SESSION_BUSY.retryAfterSeconds()).isEmpty();
    }

    @Test
    void runtimeApiExceptionCarriesOnlyErrorCode() {
        RuntimeApiException error = new RuntimeApiException(RuntimeErrorCode.INTERNAL_ERROR);

        assertThat(error.getMessage()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.errorCode()).isEqualTo(RuntimeErrorCode.INTERNAL_ERROR);
        assertThat(error.getCause()).isNull();
        assertThat(error.getStackTrace()).isEmpty();
    }
}
