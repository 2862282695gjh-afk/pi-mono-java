/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.LinkedHashMap;
import java.util.Map;

import com.campusclaw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;
import com.campusclaw.codingagent.runtimeapi.vo.SessionEventResponseVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * 把 v2 公共响应转换为不含 SSE event/id 字段的 data 帧。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeV2EventEncoder {
    private final CommittedEventProjection projection;

    private final ObjectMapper objectMapper;

    public RuntimeV2EventEncoder(CommittedEventProjection projection, ObjectMapper objectMapper) {
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    public RuntimeSseEventVO committed(CommittedEventDTO event) {
        return response(projection.project(event));
    }

    public RuntimeSseEventVO response(SessionEventResponseVO response) {
        Map<String, Object> complete = objectMapper.convertValue(response, new TypeReference<>() {});
        String type = String.valueOf(complete.remove("type"));
        return RuntimeSseEventVO.dataOnly(type, new LinkedHashMap<>(complete));
    }
}
