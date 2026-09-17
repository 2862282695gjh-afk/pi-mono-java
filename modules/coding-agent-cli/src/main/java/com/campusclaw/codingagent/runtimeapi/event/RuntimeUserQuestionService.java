/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.tool.question.QuestionAnswerDTO;
import com.campusclaw.codingagent.tool.question.QuestionAnswerResultDTO;
import com.campusclaw.codingagent.tool.question.QuestionRequestParser;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

/**
 * 按已提交问题事件严格校验整批用户答案。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class RuntimeUserQuestionService {
    private static final Set<String> QUESTION_EVENT_FIELDS = Set.of("sourceEventId", "toolCallId", "questions");

    private static final Set<String> SHOWCASE_EVENT_FIELDS = Set.of("sourceEventId", "toolCallId", "label");

    private static final Set<String> SHOWCASE_CONTEXT_EVENT_FIELDS =
            Set.of("sourceEventId", "toolCallId", "label", "context");

    private final RuntimeSessionRepository sessions;

    private final QuestionRequestParser parser;

    private final ObjectMapper objectMapper;

    public RuntimeUserQuestionService(
            RuntimeSessionRepository sessions, QuestionRequestParser parser, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    /**
     * 依据目标问题事件规范化答案，保留问题与选项原顺序。
     *
     * @param sessionId Session 标识
     * @param targetEventId 目标问题事件标识
     * @param answers 未经信任的整批答案
     * @return 可信答案
     * @throws RuntimeApiException 目标问题不存在或答案无效时抛出
     */
    public QuestionAnswerResultDTO normalizeCurrent(
            String sessionId, String targetEventId, List<QuestionAnswerDTO> answers) {
        var event = sessions.findCommittedEvent(sessionId, targetEventId);
        if (event.isEmpty()) {
            throw new RuntimeApiException(RuntimeErrorCode.QUESTION_ANSWER_INVALID);
        }
        if (!CommittedEventType.AGENT_QUESTION.value().equals(event.get().getType())) {
            throw new RuntimeApiException(RuntimeErrorCode.QUESTION_ANSWER_NOT_PENDING);
        }
        try {
            JsonNode payload = objectMapper.readTree(event.get().getPayload());
            if (isShowcasePayload(payload)) {
                return normalizeShowcaseAnswers(answers);
            }
            requireStructuredQuestionPayload(payload);
            Map<String, Object> arguments = Map.of(
                    "questions",
                    objectMapper.convertValue(payload.get("questions"), new TypeReference<List<Object>>() {}));
            return parser.normalizeAnswers(parser.parseArguments(arguments), answers);
        } catch (RuntimeApiException error) {
            throw error;
        } catch (RuntimeException | java.io.IOException error) {
            throw new RuntimeApiException(RuntimeErrorCode.QUESTION_ANSWER_INVALID, error);
        }
    }

    private QuestionAnswerResultDTO normalizeShowcaseAnswers(List<QuestionAnswerDTO> answers) {
        return parser.normalizeTextAnswer(ClawConstants.RuntimeApi.SHOWCASE_CONFIRMATION_ANSWER_ID, answers);
    }

    private static boolean isShowcasePayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return false;
        }
        Set<String> names = fieldNames(payload);
        if (!names.equals(SHOWCASE_EVENT_FIELDS) && !names.equals(SHOWCASE_CONTEXT_EVENT_FIELDS)) {
            return false;
        }
        boolean identityValid = payload.get("sourceEventId").isTextual()
                && payload.get("toolCallId").isTextual()
                && payload.get("label").isTextual();
        return identityValid
                && (!payload.has("context") || payload.get("context").isObject());
    }

    private static void requireStructuredQuestionPayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("question event payload is invalid");
        }
        Set<String> names = fieldNames(payload);
        if (!names.equals(QUESTION_EVENT_FIELDS)
                || !payload.get("sourceEventId").isTextual()
                || !payload.get("toolCallId").isTextual()
                || !payload.get("questions").isArray()) {
            throw new IllegalArgumentException("question event payload is invalid");
        }
    }

    private static Set<String> fieldNames(JsonNode payload) {
        Set<String> names = new HashSet<>();
        payload.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
