/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * Runtime Session 非分支运行记录的数据库传输对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class RuntimeRecordDTO {
    private String sessionId;

    private String id;

    private long recordSeq;

    private String lane;

    private String runId;

    private String type;

    private OffsetDateTime timestamp;

    private String payload;
}
