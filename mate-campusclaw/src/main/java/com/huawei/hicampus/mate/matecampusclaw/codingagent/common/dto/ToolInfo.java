/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.dto;

import java.util.Map;

import lombok.Data;

/**
 * Tool metadata entry returned in the {@code result.data} array of the Mate
 * inner gateway QUERYTOOLS response.
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
