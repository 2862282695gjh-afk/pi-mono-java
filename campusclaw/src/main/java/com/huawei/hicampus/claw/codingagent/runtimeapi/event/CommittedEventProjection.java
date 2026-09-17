/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentMessageResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentPlanResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentQuestionResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentThinkingResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentTodoUpdatedResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentToolCallResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.AgentToolResultResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.CostResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.EventDetailsResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.FileContentResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.QuestionAnswerResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.QuestionOptionResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.QuestionResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.SessionCompactedResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.SessionModeChangedResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.SessionModelChangedResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.SessionStatusIdleResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.SessionThinkingChangedResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.TextContentResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.TodoItemResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.UsageResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.UserContentResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.UserInterruptResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.UserMessageResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.UserPlanConfirmationResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.UserQuestionAnswerResponseVO;
import static com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO.UserToolConfirmationResponseVO;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.CommittedEventDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.SessionEventResponseVO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionDTO;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestParser;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 将权威持久化事件严格投影为完整公共事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class CommittedEventProjection {
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final ObjectMapper objectMapper;

    private final QuestionRequestParser questionParser;

    public CommittedEventProjection(ObjectMapper objectMapper) {
        this(objectMapper, new QuestionRequestParser(objectMapper));
    }

    @Autowired
    public CommittedEventProjection(ObjectMapper objectMapper, QuestionRequestParser questionParser) {
        this.objectMapper = objectMapper;
        this.questionParser = questionParser;
    }

    /**
     * 投影一个已提交事件；持久化记录不安全时拒绝整个读取。
     *
     * @param event 已提交事件
     * @return 类型化公共事件
     */
    public SessionEventResponseVO project(CommittedEventDTO event) {
        requireEventCore(event);
        try {
            CommittedEventType type = CommittedEventType.fromValue(event.getType());
            JsonNode payload = objectMapper.readTree(event.getPayload());
            requireObject(payload);
            EventDetailsResponseVO details = projectDetails(type, payload);
            String createdAt = EVENT_TIME_FORMATTER.format(event.getCreatedAt().toInstant());
            return new SessionEventResponseVO(event.getEventId(), type.value(), createdAt, details);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw invalid(exception);
        }
    }

    private EventDetailsResponseVO projectDetails(CommittedEventType type, JsonNode payload) {
        return switch (type) {
            case USER_MESSAGE -> projectUserMessage(payload);
            case USER_INTERRUPT -> new UserInterruptResponseVO(requiredText(payload, "targetEventId"));
            case USER_TOOL_CONFIRMATION -> projectConfirmation(payload);
            case USER_PLAN_CONFIRMATION -> projectPlanConfirmation(payload);
            case USER_QUESTION_ANSWER -> projectQuestionAnswer(payload);
            case AGENT_MESSAGE -> projectAgentMessage(payload);
            case AGENT_THINKING -> projectAgentThinking(payload);
            case AGENT_TOOL_CALL -> projectToolCall(payload);
            case AGENT_TOOL_RESULT -> projectToolResult(payload);
            case AGENT_PLAN -> projectPlan(payload);
            case AGENT_QUESTION -> projectQuestion(payload);
            case AGENT_TODO_UPDATED -> projectTodoUpdated(payload);
            case SESSION_STATUS_IDLE -> projectIdle(payload);
            case SESSION_MODEL_CHANGED -> projectModelChanged(payload);
            case SESSION_THINKING_CHANGED -> projectThinkingChanged(payload);
            case SESSION_MODE_CHANGED -> projectModeChanged(payload);
            case SESSION_COMPACTED -> projectCompacted(payload);
        };
    }

    private UserMessageResponseVO projectUserMessage(JsonNode payload) {
        JsonNode content = requiredArray(payload, "content");
        if (content.isEmpty() || content.size() > 5) {
            throw invalid(null);
        }
        List<UserContentResponseVO> blocks = new ArrayList<>();
        Set<String> fileIds = new java.util.HashSet<>();
        boolean hasText = false;
        for (JsonNode block : content) {
            String type = requiredText(block, "type");
            if ("text".equals(type) && !hasText && blocks.isEmpty()) {
                blocks.add(new TextContentResponseVO(type, requiredMessageText(block)));
                hasText = true;
            } else if ("file".equals(type) && fileIds.size() < ClawConstants.RuntimeApi.MAX_EVENT_FILE_IDS) {
                String fileId = requiredFileId(block);
                if (!fileIds.add(fileId)) {
                    throw invalid(null);
                }
                blocks.add(new FileContentResponseVO(type, fileId));
            } else {
                throw invalid(null);
            }
        }
        return new UserMessageResponseVO(List.copyOf(blocks));
    }

    private UserToolConfirmationResponseVO projectConfirmation(JsonNode payload) {
        String result = requiredOneOf(payload, "result", Set.of("allow", "deny"));
        String denyMessage = optionalText(payload, "denyMessage");
        if (("allow".equals(result) && denyMessage != null)
                || (denyMessage != null
                        && denyMessage.length() > ClawConstants.RuntimeApi.MAX_DENY_MESSAGE_CHARACTERS)) {
            throw invalid(null);
        }
        return new UserToolConfirmationResponseVO(requiredText(payload, "toolCallId"), result, denyMessage);
    }

    private UserPlanConfirmationResponseVO projectPlanConfirmation(JsonNode payload) {
        String result = requiredOneOf(payload, "result", Set.of("approve", "revise"));
        String feedback = optionalText(payload, "feedback");
        if (("approve".equals(result) && feedback != null)
                || (feedback != null && feedback.length() > ClawConstants.RuntimeApi.MAX_PLAN_FEEDBACK_CHARACTERS)) {
            throw invalid(null);
        }
        String target = requiredText(payload, "targetEventId");
        if (target.length() > 128) {
            throw invalid(null);
        }
        return new UserPlanConfirmationResponseVO(target, result, feedback);
    }

    private UserQuestionAnswerResponseVO projectQuestionAnswer(JsonNode payload) {
        requireFields(payload, Set.of("targetEventId", "answers"));
        String target = requiredText(payload, "targetEventId");
        JsonNode answers = requiredArray(payload, "answers");
        if (target.length() > 128 || answers.isEmpty() || answers.size() > ClawConstants.RuntimeApi.MAX_QUESTIONS) {
            throw invalid(null);
        }
        List<QuestionAnswerResponseVO> projected = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode answer : answers) {
            projected.add(projectQuestionAnswerItem(answer, ids));
        }
        return new UserQuestionAnswerResponseVO(target, List.copyOf(projected));
    }

    private QuestionAnswerResponseVO projectQuestionAnswerItem(JsonNode answer, Set<String> ids) {
        requireFields(answer, Set.of("id", "selected"), Set.of("custom"));
        String id = requiredText(answer, "id");
        JsonNode selected = requiredArray(answer, "selected");
        if (!ClawConstants.RuntimeApi.QUESTION_ID_PATTERN.matcher(id).matches()
                || !ids.add(id)
                || selected.size() > ClawConstants.RuntimeApi.MAX_QUESTION_OPTIONS) {
            throw invalid(null);
        }
        List<String> labels = new ArrayList<>();
        selected.forEach(item -> labels.add(requiredStringValue(item)));
        if (labels.stream().distinct().count() != labels.size()
                || labels.stream()
                        .anyMatch(value -> value.isEmpty()
                                || value.length() > ClawConstants.RuntimeApi.MAX_QUESTION_OPTION_LABEL_CHARACTERS)) {
            throw invalid(null);
        }
        String custom = optionalText(answer, "custom");
        if (custom != null
                && (custom.length() > ClawConstants.RuntimeApi.MAX_QUESTION_CUSTOM_ANSWER_CHARACTERS
                        || !custom.equals(custom.strip()))) {
            throw invalid(null);
        }
        return new QuestionAnswerResponseVO(id, List.copyOf(labels), custom);
    }

    private AgentPlanResponseVO projectPlan(JsonNode payload) {
        String content = requiredString(payload, "content");
        if (content.length() > ClawConstants.RuntimeApi.MAX_PLAN_CHARACTERS || !hasLevelOneHeading(content)) {
            throw invalid(null);
        }
        return new AgentPlanResponseVO(
                requiredText(payload, "sourceEventId"), requiredText(payload, "toolCallId"), content);
    }

    private AgentQuestionResponseVO projectQuestion(JsonNode payload) {
        if (payload.has("label")) {
            return projectShowcaseQuestion(payload);
        }
        requireFields(payload, Set.of("sourceEventId", "toolCallId", "questions"));
        Map<String, Object> arguments = Map.of(
                "questions",
                objectMapper.convertValue(requiredArray(payload, "questions"), new TypeReference<List<Object>>() {}));
        var snapshot = questionParser.parseArguments(arguments);
        List<QuestionResponseVO> questions =
                snapshot.questions().stream().map(this::projectQuestionItem).toList();
        return new AgentQuestionResponseVO(
                requiredText(payload, "sourceEventId"), requiredText(payload, "toolCallId"), questions, null, null);
    }

    private AgentQuestionResponseVO projectShowcaseQuestion(JsonNode payload) {
        Set<String> fields = payload.has("context")
                ? Set.of("sourceEventId", "toolCallId", "label", "context")
                : Set.of("sourceEventId", "toolCallId", "label");
        requireFields(payload, fields);
        JsonNode context = payload.get("context");
        if (context != null && !context.isObject()) {
            throw invalid(null);
        }
        return new AgentQuestionResponseVO(
                requiredText(payload, "sourceEventId"),
                requiredText(payload, "toolCallId"),
                null,
                requiredString(payload, "label"),
                context);
    }

    private QuestionResponseVO projectQuestionItem(QuestionDTO question) {
        List<QuestionOptionResponseVO> options = question.options().stream()
                .map(option -> new QuestionOptionResponseVO(option.label(), option.description()))
                .toList();
        return new QuestionResponseVO(
                question.id(), question.question(), question.header(), options, question.multiSelect());
    }

    private static boolean hasLevelOneHeading(String plan) {
        return plan.lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .filter(line -> line.startsWith("# "))
                .isPresent();
    }

    private AgentMessageResponseVO projectAgentMessage(JsonNode payload) {
        return new AgentMessageResponseVO(
                requiredCompleted(payload),
                requiredString(payload, "content"),
                requiredText(payload, "sourceEventId"),
                optionalUsage(payload));
    }

    private AgentThinkingResponseVO projectAgentThinking(JsonNode payload) {
        if (payload.has("usage")) {
            throw invalid(null);
        }
        return new AgentThinkingResponseVO(
                requiredCompleted(payload), requiredString(payload, "content"), requiredText(payload, "sourceEventId"));
    }

    private AgentToolCallResponseVO projectToolCall(JsonNode payload) {
        JsonNode arguments = requiredObject(payload, "arguments");
        Map<String, Object> values = objectMapper.convertValue(arguments, new TypeReference<>() {});
        return new AgentToolCallResponseVO(
                requiredText(payload, "toolCallId"),
                requiredText(payload, "toolName"),
                immutableMap(values),
                requiredBoolean(payload, "requiresConfirmation"),
                requiredText(payload, "sourceEventId"));
    }

    private AgentToolResultResponseVO projectToolResult(JsonNode payload) {
        JsonNode content = requiredArray(payload, "content");
        if (content.isEmpty()) {
            throw invalid(null);
        }
        List<TextContentResponseVO> blocks = new ArrayList<>();
        content.forEach(block -> blocks.add(
                new TextContentResponseVO(requiredExact(block, "type", "text"), requiredString(block, "text"))));
        boolean isError = requiredBoolean(payload, "isError");
        String errorCode = optionalText(payload, "errorCode");
        if ((isError && (errorCode == null || !ClawConstants.RuntimeApi.TOOL_ERROR_CODES.contains(errorCode)))
                || (!isError && errorCode != null)) {
            throw invalid(null);
        }
        return new AgentToolResultResponseVO(
                requiredText(payload, "toolCallId"),
                List.copyOf(blocks),
                isError,
                errorCode,
                requiredText(payload, "sourceEventId"));
    }

    private AgentTodoUpdatedResponseVO projectTodoUpdated(JsonNode payload) {
        JsonNode todos = requiredArray(payload, "todos");
        if (todos.size() > ClawConstants.RuntimeApi.MAX_TODOS) {
            throw invalid(null);
        }
        List<TodoItemResponseVO> items = new ArrayList<>();
        Set<String> contents = new java.util.HashSet<>();
        int active = 0;
        for (JsonNode todo : todos) {
            String content = requiredText(todo, "content");
            String status = requiredOneOf(todo, "status", Set.of("pending", "in_progress", "completed"));
            if (content.length() > ClawConstants.RuntimeApi.MAX_TODO_CONTENT_CHARACTERS || !contents.add(content)) {
                throw invalid(null);
            }
            active += "in_progress".equals(status) ? 1 : 0;
            items.add(new TodoItemResponseVO(content, status));
        }
        if (active > 1) {
            throw invalid(null);
        }
        return new AgentTodoUpdatedResponseVO(
                requiredText(payload, "sourceEventId"), requiredText(payload, "toolCallId"), List.copyOf(items));
    }

    private SessionStatusIdleResponseVO projectIdle(JsonNode payload) {
        String reason = requiredOneOf(payload, "reason", Set.of("done", "failed", "terminated", "confirming"));
        String errorCode = optionalText(payload, "errorCode");
        String message = optionalText(payload, "message");
        if ("failed".equals(reason)) {
            if (errorCode == null
                    || !ClawConstants.RuntimeApi.EXECUTION_ERROR_CODES.contains(errorCode)
                    || message == null) {
                throw invalid(null);
            }
        } else if (errorCode != null || message != null) {
            throw invalid(null);
        }
        return new SessionStatusIdleResponseVO(reason, requiredText(payload, "sourceEventId"), errorCode, message);
    }

    private SessionModelChangedResponseVO projectModelChanged(JsonNode payload) {
        return new SessionModelChangedResponseVO(
                requiredText(payload, "previousModelId"),
                requiredText(payload, "modelId"),
                requiredOneOf(payload, "reason", Set.of("requested", "agentRefresh")));
    }

    private SessionThinkingChangedResponseVO projectThinkingChanged(JsonNode payload) {
        return new SessionThinkingChangedResponseVO(
                requiredBoolean(payload, "previousThinking"),
                requiredBoolean(payload, "thinking"),
                requiredOneOf(payload, "reason", Set.of("requested", "modelCapability")));
    }

    private SessionModeChangedResponseVO projectModeChanged(JsonNode payload) {
        String reason = requiredOneOf(payload, "reason", Set.of("requested", "planApproved"));
        String previousMode = requiredOneOf(payload, "previousMode", ClawConstants.Agent.MODES);
        String mode = requiredOneOf(payload, "mode", ClawConstants.Agent.MODES);
        String sourceEventId = optionalText(payload, "sourceEventId");
        String confirmationEventId = optionalText(payload, "confirmationEventId");
        if (previousMode.equals(mode)
                || !hasValidModeChangeReferences(reason, sourceEventId, confirmationEventId)
                || !hasValidApprovedModeChange(reason, previousMode, mode)) {
            throw invalid(null);
        }
        return new SessionModeChangedResponseVO(previousMode, mode, reason, sourceEventId, confirmationEventId);
    }

    private static boolean hasValidModeChangeReferences(
            String reason, String sourceEventId, String confirmationEventId) {
        if ("requested".equals(reason)) {
            return sourceEventId == null && confirmationEventId == null;
        }
        return sourceEventId != null && confirmationEventId != null;
    }

    private static boolean hasValidApprovedModeChange(String reason, String previousMode, String mode) {
        return !"planApproved".equals(reason)
                || (ClawConstants.Agent.MODE_PLAN.equals(previousMode) && ClawConstants.Agent.MODE_BUILD.equals(mode));
    }

    private SessionCompactedResponseVO projectCompacted(JsonNode payload) {
        String reason = requiredOneOf(payload, "reason", Set.of("manual", "threshold", "overflow"));
        String sourceEventId = optionalText(payload, "sourceEventId");
        if (("manual".equals(reason) && sourceEventId != null) || (!"manual".equals(reason) && sourceEventId == null)) {
            throw invalid(null);
        }
        return new SessionCompactedResponseVO(
                reason,
                requiredNonNegativeLong(payload, "tokensBefore"),
                requiredNonNegativeLong(payload, "estimatedTokensAfter"),
                sourceEventId);
    }

    private UsageResponseVO optionalUsage(JsonNode payload) {
        if (!payload.has("usage")) {
            return null;
        }
        JsonNode usage = requiredObject(payload, "usage");
        CostResponseVO cost = usage.has("cost") ? projectCost(requiredObject(usage, "cost")) : null;
        return new UsageResponseVO(
                requiredNonNegativeLong(usage, "input"),
                requiredNonNegativeLong(usage, "output"),
                requiredNonNegativeLong(usage, "cacheRead"),
                requiredNonNegativeLong(usage, "cacheWrite"),
                requiredNonNegativeLong(usage, "totalTokens"),
                cost);
    }

    private CostResponseVO projectCost(JsonNode cost) {
        return new CostResponseVO(
                requiredNonNegativeDecimal(cost, "input"),
                requiredNonNegativeDecimal(cost, "output"),
                requiredNonNegativeDecimal(cost, "cacheRead"),
                requiredNonNegativeDecimal(cost, "cacheWrite"),
                requiredNonNegativeDecimal(cost, "total"));
    }

    private void requireEventCore(CommittedEventDTO event) {
        if (event == null
                || isBlank(event.getEventId())
                || isBlank(event.getType())
                || event.getCreatedAt() == null
                || isBlank(event.getPayload())) {
            throw invalid(null);
        }
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid(null);
        }
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireObject(value);
        return value;
    }

    private JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw invalid(null);
        }
        return value;
    }

    private String requiredCompleted(JsonNode parent) {
        return requiredExact(parent, "phase", "completed");
    }

    private String requiredMessageText(JsonNode block) {
        String text = requiredText(block, "text");
        if (text.length() > ClawConstants.RuntimeApi.MAX_MESSAGE_CHARACTERS) {
            throw invalid(null);
        }
        return text;
    }

    private String requiredFileId(JsonNode block) {
        String fileId = requiredText(block, "fileId");
        if (!ClawConstants.RuntimeApi.EVENT_FILE_ID_PATTERN.matcher(fileId).matches()) {
            throw invalid(null);
        }
        return fileId;
    }

    private String requiredExact(JsonNode parent, String field, String expected) {
        String value = requiredText(parent, field);
        if (!expected.equals(value)) {
            throw invalid(null);
        }
        return value;
    }

    private String requiredOneOf(JsonNode parent, String field, Set<String> allowed) {
        String value = requiredText(parent, field);
        if (!allowed.contains(value)) {
            throw invalid(null);
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field) {
        String value = requiredString(parent, field);
        if (value.isBlank()) {
            throw invalid(null);
        }
        return value;
    }

    private String requiredString(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(null);
        }
        return value.textValue();
    }

    private String requiredStringValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid(null);
        }
        return value.textValue();
    }

    private void requireFields(JsonNode node, Set<String> required) {
        requireFields(node, required, Set.of());
    }

    private void requireFields(JsonNode node, Set<String> required, Set<String> optional) {
        requireObject(node);
        Set<String> fields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        if (!fields.containsAll(required) || !allowed.containsAll(fields)) {
            throw invalid(null);
        }
    }

    private String optionalText(JsonNode parent, String field) {
        return parent.has(field) ? requiredText(parent, field) : null;
    }

    private boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(null);
        }
        return value.booleanValue();
    }

    private long requiredNonNegativeLong(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(null);
        }
        return value.longValue();
    }

    private BigDecimal requiredNonNegativeDecimal(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber() || value.decimalValue().signum() < 0) {
            throw invalid(null);
        }
        return value.decimalValue();
    }

    private Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            nested.forEach((key, item) -> copy.put(String.valueOf(key), immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> nested) {
            return Collections.unmodifiableList(
                    nested.stream().map(this::immutableValue).toList());
        }
        return value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private RuntimeApiException invalid(Exception cause) {
        return cause == null
                ? new RuntimeApiException(RuntimeErrorCode.EVENT_LIST_FAILED)
                : new RuntimeApiException(RuntimeErrorCode.EVENT_LIST_FAILED, cause);
    }
}
