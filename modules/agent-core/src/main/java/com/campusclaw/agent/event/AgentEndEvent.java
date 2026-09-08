/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.event;

import java.util.List;

import com.campusclaw.ai.types.Message;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Agent 运行结束事件，携带最终消息历史和实际取消退出证据。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record AgentEndEvent(
        @JsonProperty("messages") List<Message> messages,
        @JsonProperty("cancelled") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean cancelled)
        implements AgentEvent {
    public AgentEndEvent(List<Message> messages) {
        this(messages, false);
    }
}
