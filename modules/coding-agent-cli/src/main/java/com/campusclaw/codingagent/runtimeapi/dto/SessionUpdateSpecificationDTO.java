/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * 统一 Session 更新在锁外完成校验后的不可省略语义快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class SessionUpdateSpecificationDTO {
    private Long expectedVersion;

    private boolean displayNameSubmitted;

    private String displayName;

    private boolean modelIdSubmitted;

    private String modelId;

    private boolean targetModelSupportsThinking;

    private boolean thinkingSubmitted;

    private boolean thinking;

    private boolean modeSubmitted;

    private String mode;

    private OffsetDateTime updatedAt;

    public boolean hasExecutionConfiguration() {
        return modelIdSubmitted || thinkingSubmitted || modeSubmitted;
    }
}
