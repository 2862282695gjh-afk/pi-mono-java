/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.common.dto;

import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;

/**
 * Mate 内网网关工具元数据批量查询响应中 {@code result.data} 数组的工具元数据项。
 *
 * <p>网关契约为 snake_case 键（{@code is_concurrency_safe / display_name /
 * input_schema / output_schema}），以 {@link JsonNaming} 的
 * SNAKE_CASE 策略显式映射，避免默认 Mapper 反序列化失败或静默置 null。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Data
public class ToolInfo {
    private String id;
    private String type;
    private String version;
    private String createdAt;
    private String updatedAt;
    private String permission;
    private Boolean enabled;
    private Boolean isConcurrencySafe;
    private String name;
    private String displayName;
    private String description;
    private String source;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
}
