/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

/**
 * HTTP V1 对外持久化事件和瞬时 SSE 事件类型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
public enum RuntimeEventType {
    USER_MESSAGE("user.message"),
    ASSISTANT_MESSAGE_STARTED("assistant.message.started"),
    ASSISTANT_MESSAGE_DELTA("assistant.message.delta"),
    ASSISTANT_MESSAGE_COMPLETED("assistant.message.completed"),
    TOOL_EXECUTION_STARTED("tool.execution.started"),
    TOOL_EXECUTION_COMPLETED("tool.execution.completed"),
    TOOL_RESULT("tool.result"),
    SESSION_STATUS_IDLE("session.status.idle"),
    STREAM_END("stream.end"),
    STREAM_ERROR("stream.error");

    private final String value;

    RuntimeEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RuntimeEventType fromValue(String value) {
        for (RuntimeEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported runtime event type: " + value);
    }
}
