/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 按统一字段生成模型可见的完整工具定义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ToolDefinitionFormatter {

    private ToolDefinitionFormatter() {}

    public static ObjectNode toNode(ObjectMapper mapper, String name, String description, JsonNode inputSchema) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inputSchema, "inputSchema");
        ObjectNode definition = mapper.createObjectNode();
        definition.put("name", name);
        definition.put("description", Objects.requireNonNullElse(description, ""));
        definition.set("inputSchema", inputSchema.deepCopy());
        return definition;
    }

    public static int utf8Length(ObjectMapper mapper, String name, String description, JsonNode inputSchema) {
        return toNode(mapper, name, description, inputSchema).toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
