/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * 创建 Runtime Session 的成功结果 VO。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Getter
public class CreateSessionResponseVO {
    @JsonProperty("session_id")
    private final String sessionId;

    @JsonProperty("agent_id")
    private final String agentId;

    @JsonProperty("model_id")
    private final String modelId;

    private final String state;

    private final boolean thinking;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @JsonProperty("created_at")
    private final OffsetDateTime createdAt;

    public CreateSessionResponseVO(
            String sessionId,
            String agentId,
            String modelId,
            String state,
            boolean thinking,
            OffsetDateTime createdAt) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.modelId = modelId;
        this.state = state;
        this.thinking = thinking;
        this.createdAt = createdAt;
    }
}
