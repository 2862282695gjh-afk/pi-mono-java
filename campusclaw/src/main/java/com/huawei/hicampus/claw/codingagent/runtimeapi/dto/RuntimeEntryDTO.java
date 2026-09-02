/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * Runtime Session 持久化 Entry 的数据库传输对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class RuntimeEntryDTO {
    private String sessionId;

    private String id;

    private long entrySeq;

    private String parentId;

    private String type;

    private OffsetDateTime timestamp;

    private String payload;
}
