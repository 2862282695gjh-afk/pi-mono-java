/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.util.regex.Pattern;

import com.campusclaw.codingagent.runtimeapi.RuntimeApiConstants;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

/**
 * 统一校验 HTTP V1 路径中的公共资源标识符。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/19]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public final class RuntimeIdentifierValidator {
    private static final Pattern AGENT_ID = Pattern.compile(RuntimeApiConstants.AGENT_ID_PATTERN);

    private static final Pattern SESSION_ID = Pattern.compile(RuntimeApiConstants.SESSION_ID_PATTERN);

    private RuntimeIdentifierValidator() {}

    public static void requireAgentId(String value) {
        requireIdentifier(value, AGENT_ID, RuntimeErrorCode.INVALID_AGENT_ID);
    }

    public static void requireSessionId(String value) {
        requireIdentifier(value, SESSION_ID, RuntimeErrorCode.INVALID_SESSION_ID);
    }

    private static void requireIdentifier(String value, Pattern pattern, RuntimeErrorCode errorCode) {
        if (!pattern.matcher(value).matches()) {
            throw new RuntimeApiException(errorCode);
        }
    }
}
