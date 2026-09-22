/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.mate.BusinessToolHandler;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把已验证的业务确认工具调用适配为 Runtime 用户问答并转换回答结果。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/23]
 * @since [br_eCampusCore 26.0.0]
 */
public final class BusinessConfirmationAdapter implements BusinessToolHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREPARE_OPERATION = "business.tool.confirmation.prepare";

    private static final String ANSWER_OPERATION = "business.tool.confirmation.answer";

    private final ToolEntryPoint entryPoint;

    private final UserQuestionPort questions;

    public BusinessConfirmationAdapter(ToolEntryPoint entryPoint, UserQuestionPort questions) {
        this.entryPoint = java.util.Objects.requireNonNull(entryPoint, "entryPoint");
        this.questions = questions;
    }

    @Override
    public PreparedQuestionInvocation prepare(InvocationDTO invocation) {
        requireAvailable(invocation.toolCallId());
        QuestionRequestSnapshotDTO snapshot = toQuestionRequest(invocation);
        return new QuestionInvocation(this, invocation, snapshot);
    }

    private QuestionRequestSnapshotDTO toQuestionRequest(InvocationDTO invocation) {
        Map<String, Object> arguments = invocation.businessArguments();
        Object label = arguments.get("label");
        if (!(label instanceof String text)) {
            throw invalid(invocation, "/label", "confirm_with_user label must be a string");
        }
        JsonNode context = null;
        if (arguments.containsKey("context")) {
            context = MAPPER.valueToTree(arguments.get("context"));
            if (!context.isObject()) {
                throw invalid(invocation, "/context", "confirm_with_user context must be an object");
            }
        }
        return QuestionRequestSnapshotDTO.businessConfirmation(text, context);
    }

    private static ToolArgumentsInvalidException invalid(InvocationDTO invocation, String path, String message) {
        return new ToolArgumentsInvalidException(
                message,
                invocation.targetName(),
                invocation.targetDescription(),
                invocation.inputSchema(),
                List.of(new ToolArgumentsInvalidException.ValidationError(path, message)),
                invocation.searchableDefinition());
    }

    private AgentToolResult execute(String toolCallId, CancellationToken signal) throws Exception {
        if (signal == null || signal.isCancelled()) {
            throw new CancellationException("User confirmation was cancelled");
        }
        QuestionAnswerResultDTO result = questions.await(toolCallId, signal);
        String answer = requireAnswer(toolCallId, result);
        String content = MAPPER.writeValueAsString(Map.of("content", answer));
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(content)), null);
    }

    private void requireAvailable(String toolCallId) {
        if (entryPoint != ToolEntryPoint.RUNTIME) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_ENTRY_UNSUPPORTED_ERROR_CODE,
                    toolCallId,
                    Map.of("entryPoint", entryPoint));
        }
        if (questions == null) {
            throw failure(ClawConstants.RuntimeApi.USER_QUESTION_PORT_MISSING_ERROR_CODE, toolCallId, Map.of());
        }
    }

    private static String requireAnswer(String toolCallId, QuestionAnswerResultDTO result) {
        if (result == null || result.answers() == null || result.answers().size() != 1) {
            throw answerInvalid(toolCallId);
        }
        QuestionAnswerDTO answer = result.answers().getFirst();
        if (invalidAnswer(answer)) {
            throw answerInvalid(toolCallId);
        }
        return answer.custom();
    }

    private static boolean invalidAnswer(QuestionAnswerDTO answer) {
        return answer == null
                || !ClawConstants.RuntimeApi.BUSINESS_CONFIRMATION_ANSWER_ID.equals(answer.id())
                || answer.selected() == null
                || !answer.selected().isEmpty()
                || answer.custom() == null;
    }

    private static UserQuestionException answerInvalid(String toolCallId) {
        return UserQuestionException.failure(
                ClawConstants.RuntimeApi.USER_QUESTION_ANSWER_INVALID_ERROR_CODE,
                ANSWER_OPERATION,
                Map.of("toolCallId", toolCallId),
                null);
    }

    private static UserQuestionException failure(String errorCode, String toolCallId, Map<String, ?> context) {
        Map<String, Object> diagnostic = new java.util.LinkedHashMap<>();
        diagnostic.put("toolCallId", toolCallId);
        diagnostic.putAll(context);
        return UserQuestionException.failure(errorCode, PREPARE_OPERATION, Map.copyOf(diagnostic), null);
    }

    private record QuestionInvocation(
            BusinessConfirmationAdapter adapter,
            BusinessToolHandler.InvocationDTO invocation,
            QuestionRequestSnapshotDTO questionRequest)
            implements PreparedQuestionInvocation {

        @Override
        public String toolCallId() {
            return invocation.toolCallId();
        }

        @Override
        public String toolName() {
            return invocation.toolName();
        }

        @Override
        public Map<String, Object> arguments() {
            return invocation.arguments();
        }

        @Override
        public String authorizationTargetName() {
            return invocation.targetName();
        }

        @Override
        public String authorizationTargetId() {
            return invocation.targetId();
        }

        @Override
        public AgentToolResult execute(CancellationToken signal, AgentToolUpdateCallback onUpdate) throws Exception {
            return adapter.execute(toolCallId(), signal);
        }
    }
}
