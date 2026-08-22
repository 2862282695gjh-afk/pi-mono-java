/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;

/**
 * Runtime SSE 事件名、可选序号和 data 负载。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public class RuntimeSseEventVO {
    private final String id;

    private final String event;

    private final Map<String, Object> data;

    public RuntimeSseEventVO(String id, String event, Map<String, Object> data) {
        this.id = id;
        this.event = event;
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
