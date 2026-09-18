/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.Map;
import java.util.Optional;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingDiagnosisDTO;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeToolPermissionPolicy.BindingStatus;
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.mate.MateToolDiscovery;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在单个 Session 内把展示业务确认调用适配到既有用户问答等待。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ShowcaseConfirmationAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShowcaseConfirmationAdapter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREPARE_OPERATION = "runtime.user-question.confirmation.prepare";

    private static final String DISCOVERY_OPERATION = "runtime.user-question.confirmation.discovery";

    private static final String AWAIT_OPERATION = "runtime.user-question.confirmation.await";

    private static final String ANSWER_OPERATION = "runtime.user-question.confirmation.answer";

    private final boolean enabled;

    private final ToolEntryPoint entryPoint;

    private final UserQuestionPort questions;

    private MateToolDiscovery discovery;

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

    public synchronized void bindDiscovery(MateToolDiscovery value) {
        if (discovery != null) {
            throw new IllegalStateException("showcase confirmation discovery is already bound");
        }
        discovery = value;
    }

    public synchronized void bindPermissions(RuntimeToolPermissionPolicy value, String toolId) {
        BindingStatus status = toolId == null || toolId.isBlank() ? BindingStatus.MISSING : BindingStatus.BOUND;
        bindPermissions(value, new BindingDiagnosisDTO(status, toolId, status == BindingStatus.BOUND ? 1 : 0));
    }

    public synchronized void bindPermissions(RuntimeToolPermissionPolicy value, BindingDiagnosisDTO diagnosis) {
        if (permissions != null) {
            throw new IllegalStateException("showcase confirmation permissions are already bound");
        }
        permissions = value;
        binding = diagnosis;
    }

    public synchronized void bindContext(String valueSessionId, String valueAgentId) {
        sessionId = valueSessionId;
        agentId = valueAgentId;
    }

    public boolean matches(ToolCall call) {
        return enabled && isTarget(call == null ? null : call.name(), call == null ? null : call.arguments());
    }

    public synchronized QuestionRequestSnapshotDTO prepare(ToolCall call) {
        requireAvailable(call);
        requireBinding(call.id());
        requirePermission(call);
        requireDiscoveredIdentity(call.id());
        QuestionRequestSnapshotDTO snapshot = parseSnapshot(call.id(), call.arguments());
        PreparedCallDTO next = new PreparedCallDTO(call.id(), MAPPER.valueToTree(call.arguments()), snapshot);
        if (prepared != null) {
            if (prepared.toolCallId().equals(next.toolCallId())
                    && prepared.arguments().equals(next.arguments())) {
                return prepared.snapshot();
            }
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_PREPARATION_CONFLICT_ERROR_CODE,
                    PREPARE_OPERATION,
                    call.id(),
                    Map.of("preparedToolCallId", prepared.toolCallId()),
                    null);
        }
        prepared = next;
        return snapshot;
    }

    public synchronized Optional<QuestionRequestSnapshotDTO> prepared(ToolCall call) {
        if (!matches(call)) {
            return Optional.empty();
        }
        if (prepared == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE,
                    PREPARE_OPERATION,
                    call.id(),
                    Map.of(),
                    null);
        }
        if (!prepared.toolCallId().equals(call.id())
                || !prepared.arguments().equals(MAPPER.valueToTree(call.arguments()))) {
            throw callMismatch(call.id(), PREPARE_OPERATION);
        }
        return Optional.of(prepared.snapshot());
    }

    public Optional<String> await(String toolCallId, Map<String, Object> arguments, CancellationToken signal)
            throws Exception {
        if (!enabled || !isTarget(BuiltInToolName.CALL_MATE_TOOL.externalName(), arguments)) {
            return Optional.empty();
        }
        PreparedCallDTO current;
        synchronized (this) {
            if (entryPoint != ToolEntryPoint.RUNTIME) {
                throw entryUnsupported(toolCallId, AWAIT_OPERATION);
            }
            if (prepared == null) {
                throw failure(
                        ClawConstants.RuntimeApi.USER_QUESTION_NOT_PREPARED_ERROR_CODE,
                        AWAIT_OPERATION,
                        toolCallId,
                        Map.of(),
                        null);
            }
            if (!prepared.toolCallId().equals(toolCallId)
                    || !prepared.arguments().equals(MAPPER.valueToTree(arguments))) {
                throw callMismatch(toolCallId, AWAIT_OPERATION);
            }
            current = prepared;
        }
        try {
            if (signal == null || signal.isCancelled()) {
                throw new java.util.concurrent.CancellationException("User confirmation was cancelled");
            }
            QuestionAnswerResultDTO result = questions.await(toolCallId, signal);
            return Optional.of(requireAnswer(toolCallId, result));
        } finally {
            synchronized (this) {
                if (prepared == current) {
                    prepared = null;
                }
            }
        }
    }

    private static boolean isTarget(String callName, Map<String, Object> arguments) {
        return BuiltInToolName.CALL_MATE_TOOL.externalName().equals(callName)
                && arguments != null
                && ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME.equals(arguments.get("tool"));
    }

    private QuestionRequestSnapshotDTO parseSnapshot(String toolCallId, Map<String, Object> arguments) {
        Object args = arguments.get("args");
        if (!(args instanceof Map<?, ?> values)) {
            throw invalid(toolCallId, "args_not_object", "confirm_with_user args must be an object");
        }
        Object label = values.get("label");
        if (!(label instanceof String text)) {
            throw invalid(toolCallId, "label_not_string", "confirm_with_user label must be a string");
        }
        JsonNode context = null;
        if (values.containsKey("context")) {
            context = MAPPER.valueToTree(values.get("context"));
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

    private void requireAvailable(ToolCall call) {
        String toolCallId = call == null ? null : call.id();
        if (!matches(call) || entryPoint != ToolEntryPoint.RUNTIME) {
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
        if (discovery == null || permissions == null || binding == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_ADAPTER_UNBOUND_ERROR_CODE,
                    PREPARE_OPERATION,
                    toolCallId,
                    Map.of(),
                    null);
        }
    }

    private void requireBinding(String toolCallId) {
        if (binding.status() == BindingStatus.MISSING) {
            throw bindingFailure(ClawConstants.RuntimeApi.USER_QUESTION_BINDING_MISSING_ERROR_CODE, toolCallId);
        }
        if (binding.status() == BindingStatus.AMBIGUOUS) {
            throw bindingFailure(ClawConstants.RuntimeApi.USER_QUESTION_BINDING_AMBIGUOUS_ERROR_CODE, toolCallId);
        }
    }

    private void requirePermission(ToolCall call) {
        RuntimeToolPermissionPolicy.Decision decision = permissions.decide(call);
        if (decision == RuntimeToolPermissionPolicy.Decision.ASK) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_REQUIRED_ERROR_CODE,
                    PREPARE_OPERATION,
                    call.id(),
                    permissionContext(decision),
                    null);
        }
        if (decision == RuntimeToolPermissionPolicy.Decision.DENY) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_PERMISSION_DENIED_ERROR_CODE,
                    PREPARE_OPERATION,
                    call.id(),
                    permissionContext(decision),
                    null);
        }
    }

    private void requireDiscoveredIdentity(String toolCallId) {
        String discoveredToolId = resolveToolId(toolCallId);
        if (discoveredToolId == null) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_TOOL_NOT_FOUND_ERROR_CODE,
                    DISCOVERY_OPERATION,
                    toolCallId,
                    bindingContext(),
                    null);
        }
        if (!binding.toolId().equals(discoveredToolId)) {
            Map<String, Object> context = new java.util.LinkedHashMap<>(bindingContext());
            context.put("discoveredToolId", discoveredToolId);
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_TOOL_ID_MISMATCH_ERROR_CODE,
                    DISCOVERY_OPERATION,
                    toolCallId,
                    context,
                    null);
        }
    }

    private String resolveToolId(String toolCallId) {
        try {
            return discovery.resolveToolId(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME);
        } catch (RuntimeException error) {
            throw failure(
                    ClawConstants.RuntimeApi.USER_QUESTION_DISCOVERY_FAILED_ERROR_CODE,
                    DISCOVERY_OPERATION,
                    toolCallId,
                    bindingContext(),
                    error);
        }
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

    private UserQuestionException bindingFailure(String errorCode, String toolCallId) {
        return failure(errorCode, PREPARE_OPERATION, toolCallId, bindingContext(), null);
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

    private Map<String, Object> bindingContext() {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("bindingCount", binding.matchingCount());
        if (binding.toolId() != null && !binding.toolId().isBlank()) {
            context.put("trustedToolId", binding.toolId());
        }
        return context;
    }

    private Map<String, Object> permissionContext(RuntimeToolPermissionPolicy.Decision decision) {
        Map<String, Object> context = new java.util.LinkedHashMap<>(bindingContext());
        context.put("permissionDecision", decision.name());
        return context;
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

    private record PreparedCallDTO(String toolCallId, JsonNode arguments, QuestionRequestSnapshotDTO snapshot) {}
}
