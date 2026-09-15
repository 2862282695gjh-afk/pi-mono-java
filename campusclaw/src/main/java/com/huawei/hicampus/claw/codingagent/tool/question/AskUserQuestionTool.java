/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.question;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.ai.types.ContentBlock;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        var option = closedObject();
        option.putArray("required").add("label");
        option.withObject("properties").set("label", stringSchema(1, 160));
        option.withObject("properties").set("description", stringSchema(1, 1024));
        var question = closedObject();
        question.putArray("required").add("id").add("question");
        question.withObject("properties")
                .set("id", stringSchema(1, 64).put("pattern", ClawConstants.RuntimeApi.QUESTION_ID_REGEX));
        question.withObject("properties").set("question", stringSchema(1, 2048));
        question.withObject("properties").set("header", stringSchema(1, 64));
        question.withObject("properties").set("options", arraySchema(2, 6, option));
        question.withObject("properties")
                .set("multiSelect", MAPPER.createObjectNode().put("type", "boolean"));
        var schema = closedObject().put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.putArray("required").add("questions");
        schema.withObject("properties").set("questions", arraySchema(1, 3, question));
        return schema;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        if (signal == null || signal.isCancelled()) {
            throw new java.util.concurrent.CancellationException("User question was cancelled");
        }
        QuestionAnswerResultDTO result = questions.await(toolCallId, signal);
        String text = MAPPER.writeValueAsString(Map.of("answers", result.answers()));
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), result);
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode closedObject() {
        var node = MAPPER.createObjectNode();
        node.put("type", "object");
        node.set("properties", MAPPER.createObjectNode());
        node.put("additionalProperties", false);
        return node;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode stringSchema(int minimum, int maximum) {
        return MAPPER.createObjectNode()
                .put("type", "string")
                .put("minLength", minimum)
                .put("maxLength", maximum);
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode arraySchema(int minimum, int maximum, JsonNode item) {
        var node = MAPPER.createObjectNode()
                .put("type", "array")
                .put("minItems", minimum)
                .put("maxItems", maximum);
        node.set("items", item);
        return node;
    }
}
