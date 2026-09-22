/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.plan;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.agent.tool.ToolInputSchema;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 绑定当前响应中的完整计划并等待用户审核的 Runtime 根工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class ExitPlanModeTool implements AgentTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ObjectNode INPUT_SCHEMA = ToolInputSchema.parse(
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """);

    private static final String DESCRIPTION = "Submit the proposed plan for review while the Main Agent is in plan "
            + "mode. First write one complete Markdown plan, beginning with a level-one heading, inside "
            + "<proposed_plan> and </proposed_plan> in this same assistant response. Put each exact tag on its own "
            + "line, with any explanation before the block and no non-whitespace text after it. Do not nest blocks "
            + "or wrap them in a code fence. Then call ExitPlanMode with empty arguments {}. This must be the only "
            + "tool call in the response, with no text after it. Do not repeat the plan in arguments or end a "
            + "separate text-only response before submitting. Wait for the result: approved=true allows continuation "
            + "under the committed build mode; approved=false requires a new complete plan using the feedback. "
            + "Failed, cancelled, or unavailable review never grants approval. Business-tool permissions still apply.";

    private static final String APPROVED_GUIDANCE =
            "方案已批准，Runtime 已切换为 build。按与本次调用同一 Assistant 响应中 " + "proposed_plan 块内的获批正文继续；具体工具权限仍须遵守。";

    private static final String REVISED_GUIDANCE = "当前仍为 plan。根据 feedback 修订，准备好后重新提交完整方案。";

    private final PlanReviewPort reviews;

    public ExitPlanModeTool(PlanReviewPort reviews) {
        this.reviews = reviews;
    }

    @Override
    public String name() {
        return BuiltInToolName.EXIT_PLAN_MODE.externalName();
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
        requireEmpty(params);
        PlanReviewResultDTO result = reviews.review(toolCallId, signal);
        String guidance = result.approved() ? APPROVED_GUIDANCE : REVISED_GUIDANCE;
        var json = MAPPER.createObjectNode().put("approved", result.approved());
        if (result.feedback() != null) {
            json.put("feedback", result.feedback());
        }
        String text = MAPPER.writeValueAsString(json) + "\n\n" + guidance;
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), result);
    }

    private static void requireEmpty(Map<String, Object> params) {
        if (params == null || !params.isEmpty()) {
            throw new ToolArgumentsInvalidException("arguments must be an empty object");
        }
    }
}
