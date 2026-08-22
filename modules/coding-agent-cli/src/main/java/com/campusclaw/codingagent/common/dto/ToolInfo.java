/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import java.util.Map;

import lombok.Data;

/**
 * Mate 内网网关工具元数据批量查询响应中 {@code result.data} 数组的工具元数据项。
 *
 * <p>字段对齐网关契约：{@code id / type / version / createdAt / updatedAt /
 * permission / enabled / is_concurrency_safe / name / display_name /
 * description / source / input_schema / output_schema}。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
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
