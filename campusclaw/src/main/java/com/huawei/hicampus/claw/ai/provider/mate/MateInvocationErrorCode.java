/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.provider.mate;

/**
 * Mate 模型调用在 CampusClaw 内部使用的稳定错误码。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
public enum MateInvocationErrorCode {
    INVALID_LLM_CHAT_REQUEST(true),
    MODEL_NOT_FOUND(true),
    UNSUPPORTED_MATE_CHAT_CONTENT(true),
    INVALID_TOOL_ARGUMENTS(true),
    MODEL_RATE_LIMITED(true),
    MODEL_UNAVAILABLE(false),
    MANAGER_UNAVAILABLE(false),
    MODEL_INVOCATION_TIMEOUT(false),
    UPSTREAM_MODEL_ERROR(false),
    UPSTREAM_STREAM_ERROR(false),
    INVALID_MATE_RESPONSE(false),
    INVALID_CHAT_SSE(false),
    INVALID_REASONING_SIGNATURE(false),
    MODEL_CONTENT_FILTERED(false),
    CONTEXT_WINDOW_EXCEEDED(false),
    MATE_REQUEST_MAPPING_FAILED(false),
    MATE_MODEL_MANAGER_ERROR(false);

    private final boolean warning;

    MateInvocationErrorCode(boolean warning) {
        this.warning = warning;
    }

    boolean warning() {
        return warning;
    }

    static MateInvocationErrorCode fromUpstream(String value) {
        if (value == null || value.isBlank()) {
            return MATE_MODEL_MANAGER_ERROR;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException error) {
            return MATE_MODEL_MANAGER_ERROR;
        }
    }
}
