/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.Map;
import java.util.Optional;

import com.campusclaw.agent.tool.BeforeToolCallContext;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.PreparedToolInvocation;
import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingDiagnosisDTO;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingStatus;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在单个 Session 内把已可信解析的展示确认调用适配到既有用户问答等待。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ShowcaseConfirmationAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShowcaseConfirmationAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREPARE_OPERATION = "runtime.user-question.confirmation.prepare";
    private static final String AWAIT_OPERATION = "runtime.user-question.confirmation.await";
    private static final String ANSWER_OPERATION = "runtime.user-question.confirmation.answer";
    private static final String LEGACY_CALL_TOOL_NAME = "CallMateTool";

    private final boolean enabled;
    private final ToolEntryPoint entryPoint;
    private final UserQuestionPort questions;

    private RuntimeToolPermissionPolicy permissions;
    private BindingDiagnosisDTO binding;
    private String sessionId;
    private String agentId;
    private PreparedCallDTO prepared;

    public ShowcaseConfirmationAdapter(boolean enabled, ToolEntryPoint entryPoint, UserQuestionPort questions) {
        this.enabled = enabled;
        this.entryPoint = java.util.Objects.requireNonNull(entryPoint, "entryPoint");
        this.questions = questions;
    }

    public synchronized void bindPermissions(RuntimeToolPermissionPolicy value, String toolId) {
        BindingStatus status = toolId == null || toolId.isBlank() ? BindingStatus.MISSING : BindingStatus.BOUND;
        bindPermissions(value, new BindingDiagnosisDTO(status, toolId, status == BindingStatus.BOUND ? 1 : 0));
    }

    public synchronized void bindPermissions(RuntimeToolPermissionPolicy value, BindingDiagnosisDTO diagnosis) {
        if (permissions != null) {
            throw new IllegalStateException("showcase confirmation permissions are already bound");
        }
        permissions = java.util.Objects.requireNonNull(value, "value");
        binding = diagnosis;
    }

    public synchronized void bindContext(String valueSessionId, String valueAgentId) {
        sessionId = valueSessionId;
        agentId = valueAgentId;
    }

    public boolean matches(BeforeToolCallContext context) {
        return enabled && context != null && isTarget(context.preparedInvocation());
    }

    public synchronized QuestionRequestSnapshotDTO prepare(BeforeToolCallContext context) {
        requireAvailable(context);
        requirePermission(context);
        PreparedToolInvocation invocation = context.preparedInvocation();
        QuestionRequestSnapshotDTO snapshot = parseSnapshot(invocation.toolCallId(), businessArguments(invocation));
        PreparedCallDTO next = PreparedCallDTO.from(invocation, snapshot);
        if (prepared != null) {
            if (prepared.matches(invocation)) {
                return prepared.snapshot();
            }
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_PREPARATION_CONFLICT_ERROR_CODE,
                    PREPARE_OPERATION,
                    invocation.toolCallId(),
                    Map.of("preparedToolCallId", prepared.toolCallId()),
                    null);
        }
        prepared = next;
        return snapshot;
    }

    public synchronized Optional<QuestionRequestSnapshotDTO> prepared(BeforeToolCallContext context) {
        if (!matches(context)) {
            return Optional.empty();
        }
        PreparedToolInvocation invocation = context.preparedInvocation();
        if (prepared == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE,
                    PREPARE_OPERATION,
                    invocation.toolCallId(),
                    Map.of(),
                    null);
        }
        if (!prepared.matches(invocation)) {
            throw callMismatch(invocation.toolCallId(), PREPARE_OPERATION);
        }
        return Optional.of(prepared.snapshot());
    }

    public Optional<String> await(PreparedToolInvocation invocation, CancellationToken signal) throws Exception {
        if (!enabled || !isTarget(invocation)) {
            return Optional.empty();
        }
        PreparedCallDTO current = requirePrepared(invocation);
        try {
            if (signal == null || signal.isCancelled()) {
                throw new java.util.concurrent.CancellationException("User confirmation was cancelled");
            }
            QuestionAnswerResultDTO result = questions.await(invocation.toolCallId(), signal);
            return Optional.of(requireAnswer(invocation.toolCallId(), result));
        } finally {
            synchronized (this) {
                if (prepared == current) {
                    prepared = null;
                }
            }
        }
    }

    private synchronized PreparedCallDTO requirePrepared(PreparedToolInvocation invocation) {
        if (entryPoint != ToolEntryPoint.RUNTIME) {
            throw entryUnsupported(invocation.toolCallId(), AWAIT_OPERATION);
        }
        if (questions == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_PORT_MISSING_ERROR_CODE,
                    AWAIT_OPERATION,
                    invocation.toolCallId(),
                    Map.of(),
                    null);
        }
        if (prepared == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE,
                    AWAIT_OPERATION,
                    invocation.toolCallId(),
                    Map.of(),
                    null);
        }
        if (!prepared.matches(invocation)) {
            throw callMismatch(invocation.toolCallId(), AWAIT_OPERATION);
        }
        return prepared;
    }

    private static boolean isTarget(PreparedToolInvocation invocation) {
        if (invocation == null
                || !ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME.equals(
                        invocation.authorizationTargetName())) {
            return false;
        }
        String name = invocation.toolName();
        return ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME.equals(name)
                || BuiltInToolName.CALL_TOOL.externalName().equals(name)
                || LEGACY_CALL_TOOL_NAME.equals(name);
    }

    private static Map<String, Object> businessArguments(PreparedToolInvocation invocation) {
        Map<String, Object> arguments = invocation.arguments();
        if (ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME.equals(invocation.toolName())) {
            return arguments;
        }
        Object nested = arguments.get("args");
        if (!(nested instanceof Map<?, ?> values)) {
            throw new ToolArgumentsInvalidException("confirm_with_user args must be an object");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (name instanceof String text) {
                result.put(text, value);
            }
        });
        return PreparedToolInvocation.snapshotArguments(result);
    }

    private QuestionRequestSnapshotDTO parseSnapshot(String toolCallId, Map<String, Object> arguments) {
        Object label = arguments.get("label");
        if (!(label instanceof String text)) {
            throw invalid(toolCallId, "label_not_string", "confirm_with_user label must be a string");
        }
        JsonNode context = null;
        if (arguments.containsKey("context")) {
            context = MAPPER.valueToTree(arguments.get("context"));
            if (!context.isObject()) {
                throw invalid(toolCallId, "context_not_object", "confirm_with_user context must be an object");
            }
        }
        return QuestionRequestSnapshotDTO.showcaseConfirmation(text, context);
    }

    private String requireAnswer(String toolCallId, QuestionAnswerResultDTO result) {
        if (result == null || result.answers() == null || result.answers().size() != 1) {
            throw answerInvalid(toolCallId);
        }
        QuestionAnswerDTO answer = result.answers().getFirst();
        if (answer == null
                || !ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_ANSWER_ID.equals(answer.id())
                || answer.selected() == null
                || !answer.selected().isEmpty()
                || answer.custom() == null) {
            throw answerInvalid(toolCallId);
        }
        return answer.custom();
    }

    private void requireAvailable(BeforeToolCallContext context) {
        String toolCallId = context == null || context.toolCall() == null
                ? null
                : context.toolCall().id();
        if (!matches(context) || entryPoint != ToolEntryPoint.RUNTIME) {
            throw entryUnsupported(toolCallId, PREPARE_OPERATION);
        }
        if (questions == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_PORT_MISSING_ERROR_CODE,
                    PREPARE_OPERATION,
                    toolCallId,
                    Map.of(),
                    null);
        }
        if (permissions == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_ADAPTER_UNBOUND_ERROR_CODE,
                    PREPARE_OPERATION,
                    toolCallId,
                    Map.of(),
                    null);
        }
    }

    private void requirePermission(BeforeToolCallContext context) {
        RuntimeToolPermissionPolicy.Decision decision = permissions.decide(context);
        if (decision == RuntimeToolPermissionPolicy.Decision.ALLOW) {
            return;
        }
        String errorCode = decision == RuntimeToolPermissionPolicy.Decision.ASK
                ? ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_REQUIRED_ERROR_CODE
                : ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_DENIED_ERROR_CODE;
        throw failure(
                errorCode,
                PREPARE_OPERATION,
                context.preparedInvocation().toolCallId(),
                permissionContext(context.preparedInvocation(), decision),
                null);
    }

    private ToolArgumentsInvalidException invalid(String toolCallId, String reason, String message) {
        Map<String, Object> context = failureContext(toolCallId, Map.of("reason", reason));
        var log = LOGGER.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", PREPARE_OPERATION)
                .addKeyValue("errorCode", ClawConstants.RuntimeApi.TOOL_ARGUMENTS_INVALID_ERROR_CODE);
        context.forEach(log::addKeyValue);
        log.log(
                "CampusClaw failure: operation={}, errorCode={}, reason={}, context={}",
                PREPARE_OPERATION,
                ClawConstants.RuntimeApi.TOOL_ARGUMENTS_INVALID_ERROR_CODE,
                reason,
                context);
        return new ToolArgumentsInvalidException(message);
    }

    private UserQuestionException entryUnsupported(String toolCallId, String operation) {
        return failure(
                ClawConstants.RuntimeApi.USER_QUESTION_ENTRY_UNSUPPORTED_ERROR_CODE,
                operation,
                toolCallId,
                Map.of(),
                null);
    }

    private UserQuestionException callMismatch(String toolCallId, String operation) {
        return failure(
                ClawConstants.RuntimeApi.USER_QUESTION_CALL_MISMATCH_ERROR_CODE,
                operation,
                toolCallId,
                Map.of("preparedToolCallId", prepared.toolCallId()),
                null);
    }

    private UserQuestionException answerInvalid(String toolCallId) {
        return failure(
                ClawConstants.RuntimeApi.USER_QUESTION_ANSWER_INVALID_ERROR_CODE,
                ANSWER_OPERATION,
                toolCallId,
                Map.of(),
                null);
    }

    private Map<String, Object> permissionContext(
            PreparedToolInvocation invocation, RuntimeToolPermissionPolicy.Decision decision) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("permissionDecision", decision.name());
        context.put("trustedToolName", invocation.authorizationTargetName());
        putIfPresent(context, "trustedToolId", invocation.authorizationTargetId());
        if (binding != null) {
            context.put("bindingCount", binding.matchingCount());
        }
        return Map.copyOf(context);
    }

    private UserQuestionException failure(
            String errorCode, String operation, String toolCallId, Map<String, ?> extra, Throwable cause) {
        return UserQuestionException.failure(errorCode, operation, failureContext(toolCallId, extra), cause);
    }

    private Map<String, Object> failureContext(String toolCallId, Map<String, ?> extra) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("entryPoint", entryPoint.name());
        putIfPresent(context, "toolCallId", toolCallId);
        putIfPresent(context, "sessionId", sessionId);
        putIfPresent(context, "agentId", agentId);
        extra.forEach((name, value) -> putIfPresent(context, name, value));
        return Map.copyOf(context);
    }

    private static void putIfPresent(Map<String, Object> context, String name, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            context.put(name, value);
        }
    }

    public synchronized void clearPrepared(String toolCallId) {
        if (prepared != null && prepared.toolCallId().equals(toolCallId)) {
            prepared = null;
        }
    }

    public synchronized void clearPrepared() {
        prepared = null;
    }

    private record PreparedCallDTO(
            String toolCallId,
            String toolName,
            String targetName,
            String targetId,
            JsonNode arguments,
            QuestionRequestSnapshotDTO snapshot) {

        private static PreparedCallDTO from(PreparedToolInvocation invocation, QuestionRequestSnapshotDTO snapshot) {
            return new PreparedCallDTO(
                    invocation.toolCallId(),
                    invocation.toolName(),
                    invocation.authorizationTargetName(),
                    invocation.authorizationTargetId(),
                    MAPPER.valueToTree(invocation.arguments()),
                    snapshot);
        }

        private boolean matches(PreparedToolInvocation invocation) {
            return toolCallId.equals(invocation.toolCallId())
                    && toolName.equals(invocation.toolName())
                    && java.util.Objects.equals(targetName, invocation.authorizationTargetName())
                    && java.util.Objects.equals(targetId, invocation.authorizationTargetId())
                    && arguments.equals(MAPPER.valueToTree(invocation.arguments()));
        }
    }
}
