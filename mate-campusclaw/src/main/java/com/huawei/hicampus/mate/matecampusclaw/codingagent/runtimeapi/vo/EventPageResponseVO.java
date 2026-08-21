/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.vo;

import java.util.List;
import java.util.Map;

import lombok.Getter;

/**
 * Session 当前分支持久化事件的一页响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class EventPageResponseVO {
    private final List<Map<String, Object>> events;

    private final String nextPage;

    public EventPageResponseVO(List<Map<String, Object>> events, String nextPage) {
        this.events = List.copyOf(events);
        this.nextPage = nextPage;
    }
}
