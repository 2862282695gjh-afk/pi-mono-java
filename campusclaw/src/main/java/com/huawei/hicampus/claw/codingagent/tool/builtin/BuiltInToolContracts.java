/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 提供不可用占位工具仍需发布的稳定名称、描述和参数 Schema。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
final class BuiltInToolContracts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BuiltInToolContracts() {}

    static String description(BuiltInToolName name) {
        return switch (name) {
            case READ -> "Read the contents of a UTF-8 text file.";
            case FIND -> "Search for files by glob pattern.";
            case GREP -> "Search file contents for a pattern.";
            case LS -> "List directory contents.";
            case CRON -> "Manage scheduled tasks bound to the current managed Agent.";
            case LIST_MATE_TOOLS -> "List live Mate tools for the current Agent or one directly bound Skill.";
            case CALL_MATE_TOOL -> "Call a Mate tool by name.";
            case AGENT -> "Run one directly bound child Agent and return its final answer.";
        };
    }

    static JsonNode parameters(BuiltInToolName name, List<String> childAgentNames) {
        return switch (name) {
            case CRON -> cronSchema();
            case LIST_MATE_TOOLS -> listMateToolsSchema();
            case CALL_MATE_TOOL -> callMateToolSchema();
            case AGENT -> agentSchema(childAgentNames);
            default -> emptySchema();
        };
    }

    private static ObjectNode listMateToolsSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("skillName", stringProperty("Exact name of one Skill directly bound to the current Agent."));
        return objectSchema(properties, List.of());
    }

    private static ObjectNode callMateToolSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("tool", stringProperty("Exact Mate tool name to call."));
        properties.set(
                "args",
                MAPPER.createObjectNode()
                        .put("type", "object")
                        .put("description", "Arguments passed unchanged to the selected Mate tool."));
        return objectSchema(properties, List.of("tool"));
    }

    private static ObjectNode agentSchema(List<String> childAgentNames) {
        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode agentName = stringProperty("Exact name of one directly bound child Agent.");
        if (childAgentNames != null && !childAgentNames.isEmpty()) {
            var values = agentName.putArray("enum");
            childAgentNames.stream().sorted().forEach(values::add);
        }
        properties.set("agentName", agentName);
        properties.set("task", stringProperty("Complete task for the child Agent."));
        return objectSchema(properties, List.of("agentName", "task"));
    }

    private static ObjectNode cronSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("action", enumProperty(List.of("create", "list", "delete", "trigger", "status", "runs")));
        properties.set("name", stringProperty("Job name for create."));
        properties.set("description", stringProperty("Optional Job description for create."));
        properties.set("prompt", stringProperty("Prompt for create."));
        properties.set("schedule_type", enumProperty(List.of("at", "every", "cron")));
        properties.set("schedule_value", stringProperty("ISO timestamp, interval milliseconds, or cron expression."));
        properties.set("timezone", stringProperty("Optional timezone for a cron expression."));
        properties.set("job_id", stringProperty("Job ID for delete, trigger, status, or runs."));
        properties.set(
                "limit",
                MAPPER.createObjectNode()
                        .put("type", "integer")
                        .put("description", "Run count for runs; omitted means 10."));
        ObjectNode schema = objectSchema(properties, List.of("action"));
        var oneOf = schema.putArray("oneOf");
        oneOf.add(requiredFor("create", "name", "prompt", "schedule_type", "schedule_value"));
        oneOf.add(requiredFor("list"));
        for (String action : List.of("delete", "trigger", "status", "runs")) {
            oneOf.add(requiredFor(action, "job_id"));
        }
        return schema;
    }

    private static ObjectNode requiredFor(String action, String... fields) {
        ObjectNode branch = MAPPER.createObjectNode();
        branch.set(
                "properties",
                MAPPER.createObjectNode()
                        .set("action", MAPPER.createObjectNode().put("const", action)));
        if (fields.length > 0) {
            var required = branch.putArray("required");
            for (String field : fields) {
                required.add(field);
            }
        }
        return branch;
    }

    private static ObjectNode enumProperty(List<String> values) {
        ObjectNode property = MAPPER.createObjectNode().put("type", "string");
        var enumeration = property.putArray("enum");
        values.forEach(enumeration::add);
        return property;
    }

    private static ObjectNode stringProperty(String description) {
        return MAPPER.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode objectSchema(ObjectNode properties, List<String> required) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        if (!required.isEmpty()) {
            var requiredNode = schema.putArray("required");
            required.forEach(requiredNode::add);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode emptySchema() {
        return objectSchema(MAPPER.createObjectNode(), List.of());
    }
}
