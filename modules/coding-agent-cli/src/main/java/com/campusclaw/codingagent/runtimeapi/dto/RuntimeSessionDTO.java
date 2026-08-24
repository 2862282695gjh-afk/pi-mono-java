/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * Runtime Session 主表与 Service 之间的数据传输对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class RuntimeSessionDTO {
    private String id;

    private String agentId;

    private String modelId;

    private String state;

    private boolean thinking;

    private long resourceVersion;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private String cwd;

    private String parentSessionId;

    private String metadata;

    private String activeLeafId;
}
