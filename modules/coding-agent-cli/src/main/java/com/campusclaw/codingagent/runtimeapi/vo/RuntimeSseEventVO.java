/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

    @JsonIgnore
    private final boolean dataOnly;

    public RuntimeSseEventVO(String id, String event, Map<String, Object> data) {
        this(id, event, data, false);
    }

    private RuntimeSseEventVO(String id, String event, Map<String, Object> data, boolean dataOnly) {
        this.id = id;
        this.event = event;
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.dataOnly = dataOnly;
    }

    /**
     * 构造不使用 SSE event/id 字段的 v2 完整 data 帧。
     *
     * @param type 公共事件类型
     * @param payload 不含 type 的事件字段
     * @return 仅通过 data 发送的事件帧
     * @throws IllegalArgumentException 类型为空或 payload 重复定义 type 时抛出
     */
    public static RuntimeSseEventVO dataOnly(String type, Map<String, Object> payload) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("runtime SSE event type is required");
        }
        Objects.requireNonNull(payload, "payload");
        if (payload.containsKey("type")) {
            throw new IllegalArgumentException("runtime SSE payload must not contain type");
        }
        LinkedHashMap<String, Object> complete = new LinkedHashMap<>();
        complete.put("type", type);
        complete.putAll(payload);
        return new RuntimeSseEventVO(null, type, complete, true);
    }
}
