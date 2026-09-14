/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import java.util.List;

/**
 * 计划确认接受事务中按发送顺序提交的公共事件身份。
 *
 * @param events 已提交回执和可选模式变更事件
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record PlanConfirmationEventsDTO(List<CommittedControlEventDTO> events) {
    public PlanConfirmationEventsDTO {
        events = List.copyOf(events);
    }
}
