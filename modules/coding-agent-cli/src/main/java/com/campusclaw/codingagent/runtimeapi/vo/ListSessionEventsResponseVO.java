/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.List;

import lombok.Getter;

/**
 * Session 当前分支完整事件的整数分页响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class ListSessionEventsResponseVO {
    private final List<SessionEventResponseVO> events;

    private final Long nextPage;

    public ListSessionEventsResponseVO(List<SessionEventResponseVO> events, Long nextPage) {
        this.events = List.copyOf(events);
        this.nextPage = nextPage;
    }
}
