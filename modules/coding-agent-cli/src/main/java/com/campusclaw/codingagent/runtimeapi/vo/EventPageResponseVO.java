/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * Session 当前分支持久化事件的一页响应。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Getter
public class EventPageResponseVO {
    private final List<Map<String, Object>> events;

    @JsonProperty("next_page")
    private final String nextPage;

    public EventPageResponseVO(List<Map<String, Object>> events, String nextPage) {
        this.events = List.copyOf(events);
        this.nextPage = nextPage;
    }
}
