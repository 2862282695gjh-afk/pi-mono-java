/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import java.util.List;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具实参未通过结构或语义校验时返回稳定错误码。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class ToolArgumentsInvalidException extends IllegalArgumentException implements StableErrorCode {

    private final String toolName;

    private final String toolDescription;

    private final JsonNode inputSchema;

    private final List<ValidationError> errors;

    private final boolean searchableDefinition;

    public ToolArgumentsInvalidException(String message) {
        this(message, null, null, null, List.of(), false);
    }

    public ToolArgumentsInvalidException(
            String message,
            String toolName,
            String toolDescription,
            JsonNode inputSchema,
            List<ValidationError> errors) {
        this(message, toolName, toolDescription, inputSchema, errors, false);
    }

    public ToolArgumentsInvalidException(
            String message,
            String toolName,
            String toolDescription,
            JsonNode inputSchema,
            List<ValidationError> errors,
            boolean searchableDefinition) {
        super(message);
        this.toolName = toolName;
        this.toolDescription = toolDescription;
        this.inputSchema = inputSchema == null ? null : inputSchema.deepCopy();
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.searchableDefinition = searchableDefinition;
    }

    public boolean hasRecoveryDefinition() {
        return toolName != null && inputSchema != null && !errors.isEmpty();
    }

    public String toolName() {
        return toolName;
    }

    public String toolDescription() {
        return toolDescription;
    }

    public JsonNode inputSchema() {
        return inputSchema == null ? null : inputSchema.deepCopy();
    }

    public List<ValidationError> errors() {
        return errors;
    }

    public boolean searchableDefinition() {
        return searchableDefinition;
    }

    @Override
    public String stableErrorCode() {
        return ClawConstants.RuntimeApi.TOOL_ARGUMENTS_INVALID_ERROR_CODE;
    }

    /**
     * 单个字段或结构约束错误。
     *
     * @param path 相对参数对象的 JSON Pointer
     * @param message 不包含原始参数值的约束说明
     */
    public record ValidationError(String path, String message) {}
}
