/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import java.util.Map;

import lombok.Data;

/**
 * Mate 内网网关工具元数据批量查询响应中 {@code result.data} 数组的工具元数据项。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class ToolInfo {
    private String toolId;
    private String toolName;
    private String description;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    private Boolean isConcurrencySafe;
    private String permission;
}
