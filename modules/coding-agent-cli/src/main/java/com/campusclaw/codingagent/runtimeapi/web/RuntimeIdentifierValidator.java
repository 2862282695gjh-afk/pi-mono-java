/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.regex.Pattern;

import com.campusclaw.codingagent.common.identifier.ResourceIdentifierPatterns;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

/**
 * 统一校验 HTTP V1 路径中的公共资源标识符。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeIdentifierValidator {
    private RuntimeIdentifierValidator() {}

    public static void requireAgentId(String value) {
        requireIdentifier(value, ResourceIdentifierPatterns.AGENT_ID_PATTERN, RuntimeErrorCode.INVALID_AGENT_ID);
    }

    public static void requireSessionId(String value) {
        requireIdentifier(value, ResourceIdentifierPatterns.SESSION_ID_PATTERN, RuntimeErrorCode.INVALID_SESSION_ID);
    }

    private static void requireIdentifier(String value, Pattern pattern, RuntimeErrorCode errorCode) {
        if (!pattern.matcher(value).matches()) {
            throw new RuntimeApiException(errorCode);
        }
    }
}
