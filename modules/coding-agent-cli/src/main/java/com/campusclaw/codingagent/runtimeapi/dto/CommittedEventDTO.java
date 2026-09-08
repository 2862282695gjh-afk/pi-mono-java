/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * 已提交公共事件的数据库传输对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class CommittedEventDTO {
    private String sessionId;

    private String eventId;

    private long eventSeq;

    private String anchorEntryId;

    private String type;

    private OffsetDateTime createdAt;

    private String payload;
}
