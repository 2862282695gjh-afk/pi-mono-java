/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Getter;

/**
 * 查询 Runtime Session 当前资源的成功结果 VO。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class GetSessionResponseVO {
    private final String sessionId;

    private final String agentId;

    private final String modelId;

    private final String state;

    private final boolean thinking;

    private final UsageResponseVO lifetimeUsage;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final OffsetDateTime createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final OffsetDateTime updatedAt;

    public GetSessionResponseVO(
            String sessionId,
            String agentId,
            String modelId,
            String state,
            boolean thinking,
            UsageResponseVO lifetimeUsage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.modelId = modelId;
        this.state = state;
        this.thinking = thinking;
        this.lifetimeUsage = lifetimeUsage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
