/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import java.time.OffsetDateTime;

import lombok.Data;

/**
 * 已提交唯一终态的 Entry 与公共事件快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class CommittedTerminalDTO {
    private String sessionId;

    private String entryId;

    private long entrySeq;

    private String entryParentId;

    private String entryType;

    private OffsetDateTime entryTimestamp;

    private String entryPayload;

    private String eventId;

    private long eventSeq;

    private String anchorEntryId;

    private String eventType;

    private OffsetDateTime createdAt;

    private String eventPayload;
}
