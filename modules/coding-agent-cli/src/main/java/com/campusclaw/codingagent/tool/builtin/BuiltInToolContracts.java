/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.builtin;

import java.util.List;

import com.campusclaw.agent.tool.ToolInputSchema;
import com.campusclaw.codingagent.tool.question.AskUserQuestionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 提供不可用占位工具仍需发布的稳定名称、描述和参数 Schema。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
final class BuiltInToolContracts {

    private static final ObjectNode AGENT_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {
                "agentName": {
                  "type": "string",
                  "description": "Exact name of one directly bound child Agent."
                },
                "task": {
                  "type": "string",
                  "description": "Complete task for the child Agent."
                }
              },
              "required": ["agentName", "task"],
              "additionalProperties": false
            }
            """);

    private static final ObjectNode SKILLTOOL_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Exact name of an available Skill directly bound to the current Agent."
                }
              },
              "required": ["name"],
              "additionalProperties": false
            }
            """);

    private static final ObjectNode CALLTOOL_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {
                "tool": {
                  "type": "string",
                  "description": "Exact unique business tool name available through a directly bound Skill."
                },
                "args": {
                  "type": "object",
                  "description": "Arguments for the selected business tool; omitted means an empty object."
                }
              },
              "required": ["tool"],
              "additionalProperties": false
            }
            """);

    private static final ObjectNode SEARCHTOOLS_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {
                "tool": {
                  "type": "string",
                  "minLength": 1,
                  "description": "Exact unique business tool name referenced by the Skill instructions."
                }
              },
              "required": ["tool"],
              "additionalProperties": false
            }
            """);

    private static final ObjectNode CRONTOOL_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["create", "list", "delete", "trigger", "status", "runs"]
                },
                "name": {
                  "type": "string",
                  "description": "Job name for create."
                },
                "description": {
                  "type": "string",
                  "description": "Optional Job description for create."
                },
                "prompt": {
                  "type": "string",
                  "description": "Prompt for create."
                },
                "schedule_type": {
                  "type": "string",
                  "enum": ["at", "every", "cron"]
                },
                "schedule_value": {
                  "type": "string",
                  "description": "ISO timestamp, interval milliseconds, or cron expression."
                },
                "timezone": {
                  "type": "string",
                  "description": "Optional timezone for a cron expression."
                },
                "job_id": {
                  "type": "string",
                  "description": "Job ID for delete, trigger, status, or runs."
                },
                "limit": {
                  "type": "integer",
                  "description": "Run count for runs; omitted means 10."
                }
              },
              "required": ["action"],
              "additionalProperties": false,
              "oneOf": [
                {
                  "properties": {
                    "action": {
                      "const": "create"
                    }
                  },
                  "required": ["name", "prompt", "schedule_type", "schedule_value"]
                },
                {
                  "properties": {
                    "action": {
                      "const": "list"
                    }
                  }
                },
                {
                  "properties": {
                    "action": {
                      "const": "delete"
                    }
                  },
                  "required": ["job_id"]
                },
                {
                  "properties": {
                    "action": {
                      "const": "trigger"
                    }
                  },
                  "required": ["job_id"]
                },
                {
                  "properties": {
                    "action": {
                      "const": "status"
                    }
                  },
                  "required": ["job_id"]
                },
                {
                  "properties": {
                    "action": {
                      "const": "runs"
                    }
                  },
                  "required": ["job_id"]
                }
              ]
            }
            """);

    private static final ObjectNode EMPTY_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """);

    private BuiltInToolContracts() {}

    static String description(BuiltInToolName name) {
        return switch (name) {
            case READ -> "Read the contents of a UTF-8 text file.";
            case FIND -> "Search for files by glob pattern.";
            case GREP -> "Search file contents for a pattern.";
            case LS -> "List directory contents.";
            case CRON -> "Manage scheduled tasks bound to the current managed Agent.";
            case SKILL -> "Load instructions and resource paths for an available Skill by name.";
            case SEARCH_TOOLS ->
                "Get one complete business tool definition by its exact unique name within the current Agent's"
                        + " trusted Skill bindings. This does not register or execute the tool.";
            case CALL_TOOL ->
                "Call a managed business tool available through a directly bound Skill."
                        + " Use SearchTools when its definition is needed."
                        + " If local validation returns a trusted definition, create a new corrected call from it;"
                        + " do not guess fields or repeat unchanged arguments.";
            case AGENT -> "Run one directly bound child Agent and return its final answer.";
            case EXIT_PLAN_MODE -> "Submit the proposed plan block from this assistant response for user review.";
            case TODO_UPDATE -> "Track implementation progress in build mode.";
            case ASK_USER_QUESTION -> AskUserQuestionTool.DESCRIPTION;
        };
    }

    static JsonNode parameters(BuiltInToolName name, List<String> childAgentNames) {
        return switch (name) {
            case CRON -> cronSchema();
            case SKILL -> skillSchema();
            case SEARCH_TOOLS -> searchToolsSchema();
            case CALL_TOOL -> callToolSchema();
            case AGENT -> agentSchema(childAgentNames);
            case EXIT_PLAN_MODE -> exitPlanModeSchema();
            case TODO_UPDATE -> todoUpdateSchema();
            case ASK_USER_QUESTION ->
                new AskUserQuestionTool((toolCallId, signal) -> null)
                        .parameters()
                        .deepCopy();
            default -> emptySchema();
        };
    }

    private static ObjectNode exitPlanModeSchema() {
        return emptySchema();
    }

    private static ObjectNode todoUpdateSchema() {
        return new com.campusclaw.codingagent.tool.todo.TodoUpdateTool()
                .parameters()
                .deepCopy();
    }

    private static ObjectNode skillSchema() {
        return SKILLTOOL_SCHEMA.deepCopy();
    }

    private static ObjectNode callToolSchema() {
        return CALLTOOL_SCHEMA.deepCopy();
    }

    private static ObjectNode searchToolsSchema() {
        return SEARCHTOOLS_SCHEMA.deepCopy();
    }

    private static ObjectNode agentSchema(List<String> childAgentNames) {
        var schema = AGENT_SCHEMA.deepCopy();
        if (childAgentNames != null && !childAgentNames.isEmpty()) {
            var values = schema.withObject("properties").withObject("agentName").putArray("enum");
            childAgentNames.stream().sorted().forEach(values::add);
        }
        return schema;
    }

    private static ObjectNode cronSchema() {
        return CRONTOOL_SCHEMA.deepCopy();
    }

    private static ObjectNode emptySchema() {
        return EMPTY_SCHEMA.deepCopy();
    }
}
