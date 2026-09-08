/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

/**
 * 对外公开的完整事件类型。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public enum CommittedEventType {
    USER_MESSAGE("user.message"),
    USER_INTERRUPT("user.interrupt"),
    USER_TOOL_CONFIRMATION("user.tool_confirmation"),
    AGENT_MESSAGE("agent.message"),
    AGENT_THINKING("agent.thinking"),
    AGENT_TOOL_CALL("agent.tool_call"),
    AGENT_TOOL_RESULT("agent.tool_result"),
    SESSION_STATUS_IDLE("session.status_idle"),
    SESSION_MODEL_CHANGED("session.model_changed"),
    SESSION_THINKING_CHANGED("session.thinking_changed"),
    SESSION_COMPACTED("session.compacted");

    private final String value;

    CommittedEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * 按公共字面值解析事件类型。
     *
     * @param value 公共事件类型字面值
     * @return 完整事件类型
     * @throws IllegalArgumentException 类型字面值不属于完整事件集合时抛出
     */
    public static CommittedEventType fromValue(String value) {
        for (CommittedEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported committed event type: " + value);
    }
}
