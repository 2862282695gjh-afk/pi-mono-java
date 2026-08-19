/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

/**
 * Runtime 错误码 HTTP 语义和中英文消息目录完整性测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/19]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class RuntimeErrorCodeTest {
    @Test
    void everyErrorCodeHasEnglishAndChineseMessages() {
        ResourceBundle english = ResourceBundle.getBundle("messages", Locale.US);
        ResourceBundle chinese = ResourceBundle.getBundle("messages", Locale.SIMPLIFIED_CHINESE);

        for (RuntimeErrorCode code : RuntimeErrorCode.values()) {
            assertThat(english.containsKey(code.messageKey()))
                    .as("English message for %s", code)
                    .isTrue();
            assertThat(chinese.containsKey(code.messageKey()))
                    .as("Chinese message for %s", code)
                    .isTrue();
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
}
