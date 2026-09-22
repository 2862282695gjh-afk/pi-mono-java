/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolInputSchema;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 发布结构化问题并等待原 Runtime 根执行收到完整答案的工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class AskUserQuestionTool implements AgentTool {
    public static final String DESCRIPTION = "Ask the user one to three concise questions when required information "
            + "or a user-owned choice blocks further work. Use stable question ids, optional choices and optional "
            + "multiSelect; free text is always supported. Ask only what cannot be established by inspecting "
            + "available information. This must be the only tool call in the assistant response. Wait for the "
            + "returned answers before continuing the same task. Answers do not approve a plan, change mode, or "
            + "replace business-tool permission confirmation. Never invent an answer or treat cancellation, silence "
            + "or failure as consent.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ObjectNode INPUT_SCHEMA = ToolInputSchema.parse(String.format(
            Locale.ROOT,
            """
            {
              "type": "object",
              "properties": {
                "questions": {
                  "type": "array",
                  "minItems": 1,
                  "maxItems": 3,
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {
                        "type": "string",
                        "minLength": 1,
                        "maxLength": 64,
                        "pattern": %s
                      },
                      "question": {
                        "type": "string",
                        "minLength": 1,
                        "maxLength": 2048
                      },
                      "header": {
                        "type": "string",
                        "minLength": 1,
                        "maxLength": 64
                      },
                      "options": {
                        "type": "array",
                        "minItems": 2,
                        "maxItems": 6,
                        "items": {
                          "type": "object",
                          "properties": {
                            "label": {
                              "type": "string",
                              "minLength": 1,
                              "maxLength": 160
                            },
                            "description": {
                              "type": "string",
                              "minLength": 1,
                              "maxLength": 1024
                            }
                          },
                          "additionalProperties": false,
                          "required": ["label"]
                        }
                      },
                      "multiSelect": {
                        "type": "boolean"
                      }
                    },
                    "additionalProperties": false,
                    "required": ["id", "question"]
                  }
                }
              },
              "additionalProperties": false,
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "required": ["questions"]
            }
            """,
            MAPPER.valueToTree(ClawConstants.RuntimeApi.QUESTION_ID_REGEX)));

    private final UserQuestionPort questions;

    public AskUserQuestionTool(UserQuestionPort questions) {
        this.questions = questions;
    }

    @Override
    public String name() {
        return BuiltInToolName.ASK_USER_QUESTION.externalName();
    }

    @Override
    public String label() {
        return name();
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode parameters() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        if (signal == null || signal.isCancelled()) {
            throw new java.util.concurrent.CancellationException("User question was cancelled");
        }
        QuestionAnswerResultDTO result = questions.await(toolCallId, signal);
        if (result == null) {
            throw new IllegalStateException("User question returned no answer");
        }
        String text = MAPPER.writeValueAsString(Map.of("answers", result.answers()));
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), result);
    }
}
