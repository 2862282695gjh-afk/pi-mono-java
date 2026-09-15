/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.plan;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.agent.tool.AgentToolResult;
import com.huawei.hicampus.claw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolArgumentsInvalidException;
import com.huawei.hicampus.claw.ai.types.ContentBlock;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.codingagent.tool.builtin.BuiltInToolName;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 提交完整 Markdown 计划并等待用户审核的 Runtime 根工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class ExitPlanModeTool implements AgentTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DESCRIPTION = "Submit the complete proposed plan for user review while the root "
            + "agent is in plan mode. Pass the full Markdown beginning with a level-one heading. This must be the "
            + "only tool call in the assistant response. Wait for the result: approved=true allows continuation "
            + "under the committed build mode, while approved=false requires revision using the feedback. A failed, "
            + "cancelled, or unavailable review never grants approval. This tool does not bypass business-tool "
            + "permissions.";

    private static final String APPROVED_GUIDANCE = "方案已批准，Runtime 已切换为 build。按本次 ExitPlanMode 调用中的方案继续；"
            + "多步骤实施且 TodoUpdate 可用时先创建完整执行清单，随后逐项实施并验证。具体工具权限仍须遵守。";

    private static final String REVISED_GUIDANCE =
            "方案需要修订，当前仍为 plan。根据 feedback 完善；关键问题无法确认时向用户澄清，" + "否则重新提交完整方案。不得开始实施或记录实施进度。";

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
        var schema = MAPPER.createObjectNode().put("type", "object");
        schema.putObject("properties")
                .putObject("plan")
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", ClawConstants.RuntimeApi.MAX_PLAN_CHARACTERS);
        schema.putArray("required").add("plan");
        return schema.put("additionalProperties", false);
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        String plan = requirePlan(params);
        PlanReviewResultDTO result = reviews.await(toolCallId, plan);
        String guidance = result.approved() ? APPROVED_GUIDANCE : REVISED_GUIDANCE;
        var json = MAPPER.createObjectNode().put("approved", result.approved());
        if (result.feedback() != null) {
            json.put("feedback", result.feedback());
        }
        String text = MAPPER.writeValueAsString(json) + "\n\n" + guidance;
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), result);
    }

    private static String requirePlan(Map<String, Object> params) {
        if (params == null || !params.keySet().equals(Set.of("plan")) || !(params.get("plan") instanceof String plan)) {
            throw new ToolArgumentsInvalidException("plan must be the only parameter and must be a string");
        }
        if (plan.length() > ClawConstants.RuntimeApi.MAX_PLAN_CHARACTERS || !hasLevelOneHeading(plan)) {
            throw new ToolArgumentsInvalidException("plan must begin with a level-one heading and fit the size limit");
        }
        return plan;
    }

    private static boolean hasLevelOneHeading(String plan) {
        return plan.lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .filter(line -> line.startsWith("# "))
                .isPresent();
    }
}
