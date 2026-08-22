/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Getter;

/**
 * 控制消息进入当前活动执行队列后的成功结果 VO。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class ControlMessageAcceptedResponseVO {
    private final String sessionId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final OffsetDateTime acceptedAt;

    public ControlMessageAcceptedResponseVO(String sessionId, OffsetDateTime acceptedAt) {
        this.sessionId = sessionId;
        this.acceptedAt = acceptedAt;
    }
}
