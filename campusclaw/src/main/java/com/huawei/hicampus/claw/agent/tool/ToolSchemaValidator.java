/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;

/**
 * 使用可信工具定义校验参数并生成可供模型修正的字段错误。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ToolSchemaValidator {

    private ToolSchemaValidator() {}

    public static void validate(
            ObjectMapper mapper,
            JsonSchemaFactory schemaFactory,
            String toolName,
            String description,
            JsonNode inputSchema,
            Map<String, Object> arguments) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(schemaFactory, "schemaFactory");
        Objects.requireNonNull(inputSchema, "inputSchema");
        var messages = validateSchema(mapper, schemaFactory, inputSchema, arguments);
        if (messages.isEmpty()) {
            return;
        }
        List<ToolArgumentsInvalidException.ValidationError> errors = messages.stream()
                .map(ToolSchemaValidator::toError)
                .sorted(Comparator.comparing(ToolArgumentsInvalidException.ValidationError::path)
                        .thenComparing(ToolArgumentsInvalidException.ValidationError::message))
                .toList();
        String summary = errors.stream()
                .map(error -> error.path() + ": " + error.message())
                .reduce((left, right) -> left + "; " + right)
                .orElse("Unknown schema validation error");
        throw new ToolArgumentsInvalidException(
                "Tool arguments failed validation: " + summary, toolName, description, inputSchema, errors);
    }

    private static java.util.Set<ValidationMessage> validateSchema(
            ObjectMapper mapper, JsonSchemaFactory schemaFactory, JsonNode inputSchema, Map<String, Object> arguments) {
        try {
            var schema = schemaFactory.getSchema(inputSchema);
            return schema.validate(mapper.valueToTree(arguments));
        } catch (RuntimeException failure) {
            throw new ToolSchemaInvalidException(failure);
        }
    }

    private static ToolArgumentsInvalidException.ValidationError toError(ValidationMessage message) {
        String path = toJsonPointer(message.getInstanceLocation());
        if ("required".equals(message.getType())) {
            path = appendRequiredProperty(path, message.getProperty());
        }
        return new ToolArgumentsInvalidException.ValidationError(path, message.getMessage());
    }

    private static String appendRequiredProperty(String path, String property) {
        if (property == null || property.isBlank()) {
            return path;
        }
        return path + "/" + escapePointer(property);
    }

    private static String toJsonPointer(JsonNodePath location) {
        if (location == null || location.getNameCount() == 0) {
            return "";
        }
        StringBuilder pointer = new StringBuilder();
        for (int index = 0; index < location.getNameCount(); index++) {
            pointer.append('/').append(escapePointer(String.valueOf(location.getElement(index))));
        }
        return pointer.toString();
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
