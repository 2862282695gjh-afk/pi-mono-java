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
import com.campusclaw.codingagent.tool.builtin.BuiltInToolName;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.mate.MateToolDiscovery;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 在单个 Session 内把展示业务确认调用适配到既有用户问答等待。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ShowcaseConfirmationAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;

    private final ToolEntryPoint entryPoint;

    private final UserQuestionPort questions;

    private MateToolDiscovery discovery;

    private RuntimeToolPermissionPolicy permissions;

    private String trustedToolId;

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
        if (permissions != null) {
            throw new IllegalStateException("showcase confirmation permissions are already bound");
        }
        permissions = value;
        trustedToolId = toolId;
    }

    public boolean matches(ToolCall call) {
        return enabled && isTarget(call == null ? null : call.name(), call == null ? null : call.arguments());
    }

    public synchronized QuestionRequestSnapshotDTO prepare(ToolCall call) {
        if (!matches(call) || entryPoint != ToolEntryPoint.RUNTIME || questions == null) {
            throw unavailable();
        }
        requireBound();
        if (trustedToolId == null || permissions.decide(call) != RuntimeToolPermissionPolicy.Decision.ALLOW) {
            throw unavailable();
        }
        if (!trustedToolId.equals(resolveToolId())) {
            throw unavailable();
        }
        QuestionRequestSnapshotDTO snapshot = parseSnapshot(call.arguments());
        PreparedCallDTO next = new PreparedCallDTO(call.id(), MAPPER.valueToTree(call.arguments()), snapshot);
        if (prepared != null) {
            if (prepared.toolCallId().equals(next.toolCallId())
                    && prepared.arguments().equals(next.arguments())) {
                return prepared.snapshot();
            }
            throw unavailable();
        }
        prepared = next;
        return snapshot;
    }

    public synchronized Optional<QuestionRequestSnapshotDTO> prepared(ToolCall call) {
        if (!matches(call)) {
            return Optional.empty();
        }
        if (prepared == null
                || !prepared.toolCallId().equals(call.id())
                || !prepared.arguments().equals(MAPPER.valueToTree(call.arguments()))) {
            throw unavailable();
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
            if (entryPoint != ToolEntryPoint.RUNTIME
                    || prepared == null
                    || !prepared.toolCallId().equals(toolCallId)
                    || !prepared.arguments().equals(MAPPER.valueToTree(arguments))) {
                throw unavailable();
            }
            current = prepared;
        }
        try {
            if (signal == null || signal.isCancelled()) {
                throw new java.util.concurrent.CancellationException("User confirmation was cancelled");
            }
            QuestionAnswerResultDTO result = questions.await(toolCallId, signal);
            return Optional.of(requireAnswer(result));
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

    private static QuestionRequestSnapshotDTO parseSnapshot(Map<String, Object> arguments) {
        Object args = arguments.get("args");
        if (!(args instanceof Map<?, ?> values)) {
            throw invalid("confirm_with_user args must be an object");
        }
        Object label = values.get("label");
        if (!(label instanceof String text)) {
            throw invalid("confirm_with_user label must be a string");
        }
        JsonNode context = null;
        if (values.containsKey("context")) {
            context = MAPPER.valueToTree(values.get("context"));
            if (!context.isObject()) {
                throw invalid("confirm_with_user context must be an object");
            }
        }
        return QuestionRequestSnapshotDTO.showcaseConfirmation(text, context);
    }

    private static String requireAnswer(QuestionAnswerResultDTO result) {
        if (result == null || result.answers().size() != 1) {
            throw new IllegalStateException("showcase confirmation returned invalid answers");
        }
        QuestionAnswerDTO answer = result.answers().getFirst();
        if (!ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_ANSWER_ID.equals(answer.id())
                || !answer.selected().isEmpty()
                || answer.custom() == null) {
            throw new IllegalStateException("showcase confirmation returned invalid answers");
        }
        return answer.custom();
    }

    private void requireBound() {
        if (discovery == null || permissions == null) {
            throw unavailable();
        }
    }

    private String resolveToolId() {
        try {
            return discovery.resolveToolId(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_TOOL_NAME);
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private static ToolArgumentsInvalidException invalid(String message) {
        return new ToolArgumentsInvalidException(message);
    }

    private static UserQuestionException unavailable() {
        return new UserQuestionException(ClawConstants.RuntimeApi.USER_QUESTION_UNAVAILABLE_ERROR_CODE);
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
