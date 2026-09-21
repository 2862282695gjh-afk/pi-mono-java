/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.Objects;

import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtimeapi.dto.ExecutionTargetDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanProposalSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.PlanTextSnapshotDTO;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.codingagent.tool.plan.PlanReviewException;

/**
 * 将最终计划解析结果绑定到已提交 Assistant、固定执行和唯一退出调用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
public final class PlanProposalBinder {
    public PlanProposalSnapshotDTO bind(
            ExecutionTargetDTO target,
            String sourceEventId,
            String assistantEntryId,
            String planEventId,
            PlanTextSnapshotDTO text,
            AssistantMessage assistant,
            BeforeToolCallContext context) {
        requireCandidate(assistantEntryId, planEventId, text, assistant, context);
        ToolCall call = context.toolCall();
        requireUniqueOrderedCall(assistant, call);
        return new PlanProposalSnapshotDTO(
                Objects.requireNonNull(target, "target"),
                assistantEntryId,
                planEventId,
                requireText(sourceEventId),
                call.id(),
                text.planContent());
    }

    private static void requireCandidate(
            String assistantEntryId,
            String planEventId,
            PlanTextSnapshotDTO text,
            AssistantMessage assistant,
            BeforeToolCallContext context) {
        if (invalidTerminal(assistant) || assistant != context.assistantMessage()) {
            throw unavailable();
        }
        if (invalidIdentity(assistantEntryId) || invalidIdentity(planEventId) || text == null || !text.valid()) {
            throw unavailable();
        }
    }

    private static boolean invalidTerminal(AssistantMessage assistant) {
        return assistant == null || assistant.stopReason() != StopReason.TOOL_USE;
    }

    private static boolean invalidIdentity(String value) {
        return value == null || value.isBlank();
    }

    private static void requireUniqueOrderedCall(AssistantMessage assistant, ToolCall expected) {
        int callIndex = -1;
        int callCount = 0;
        for (int index = 0; index < assistant.content().size(); index++) {
            var block = assistant.content().get(index);
            if (block instanceof ToolCall call) {
                callCount++;
                if (call.equals(expected)) {
                    callIndex = index;
                }
            } else if (!(block instanceof TextContent) && !(block instanceof ThinkingContent)) {
                throw unavailable();
            }
        }
        if (callCount != 1 || callIndex < 0 || !validExitCall(expected) || hasNonBlankTextAfter(assistant, callIndex)) {
            throw unavailable();
        }
    }

    private static boolean validExitCall(ToolCall call) {
        return BuiltInToolName.EXIT_PLAN_MODE.externalName().equals(call.name())
                && call.arguments() != null
                && call.arguments().isEmpty();
    }

    private static boolean hasNonBlankTextAfter(AssistantMessage assistant, int callIndex) {
        return assistant.content().subList(callIndex + 1, assistant.content().size()).stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .anyMatch(text -> text != null && !text.isBlank());
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw unavailable();
        }
        return value;
    }

    private static PlanReviewException unavailable() {
        return new PlanReviewException("PLAN_REVIEW_UNAVAILABLE");
    }
}
