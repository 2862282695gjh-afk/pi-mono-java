/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * 严格解析问题调用并依据不可变问题快照规范化答案。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class QuestionRequestParser {
    private static final Set<String> REQUEST_FIELDS = Set.of("questions");

    private static final Set<String> QUESTION_FIELDS = Set.of("id", "question", "header", "options", "multiSelect");

    private static final Set<String> OPTION_FIELDS = Set.of("label", "description");

    private static final Set<String> ANSWER_EVENT_FIELDS = Set.of("targetEventId", "answers");

    private static final Set<String> ANSWER_FIELDS = Set.of("id", "selected", "custom");

    private final ObjectMapper objectMapper;

    public QuestionRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从最终且唯一的工具调用创建不可变问题快照。
     *
     * @param call 最终工具调用
     * @return 规范化的问题快照
     * @throws ToolArgumentsInvalidException 调用类型或参数无效时抛出
     */
    public QuestionRequestSnapshotDTO parse(ToolCall call) {
        if (call == null || !"AskUserQuestion".equals(call.name())) {
            throw invalid("AskUserQuestion call is required");
        }
        return parseArguments(call.arguments());
    }

    /**
     * 从工具参数创建不可变问题快照。
     *
     * @param arguments 工具参数
     * @return 规范化的问题快照
     * @throws ToolArgumentsInvalidException 参数不符合问题契约时抛出
     */
    public QuestionRequestSnapshotDTO parseArguments(Map<String, Object> arguments) {
        JsonNode root = objectMapper.valueToTree(arguments);
        requireClosedObject(root, REQUEST_FIELDS, "question request");
        JsonNode questions = root.get("questions");
        if (questions == null
                || !questions.isArray()
                || questions.isEmpty()
                || questions.size() > ClawConstants.RuntimeApi.MAX_QUESTIONS) {
            throw invalid("questions must contain one to three items");
        }
        List<QuestionDTO> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode question : questions) {
            QuestionDTO parsed = parseQuestion(question);
            if (!ids.add(parsed.id())) {
                throw invalid("question ids must be unique");
            }
            result.add(parsed);
        }
        return new QuestionRequestSnapshotDTO(result);
    }

    /**
     * 按目标问题快照校验并规范化整批答案。
     *
     * @param snapshot 不可变问题快照
     * @param answers 待校验答案
     * @return 按问题和选项原顺序排列的可信答案
     * @throws IllegalArgumentException 答案不符合目标问题约束时抛出
     */
    public QuestionAnswerResultDTO normalizeAnswers(
            QuestionRequestSnapshotDTO snapshot, List<QuestionAnswerDTO> answers) {
        if (snapshot == null
                || answers == null
                || answers.size() != snapshot.questions().size()) {
            throw new IllegalArgumentException("every question must be answered exactly once");
        }
        Map<String, QuestionAnswerDTO> byId = indexAnswers(answers);
        List<QuestionAnswerDTO> normalized = new ArrayList<>();
        for (QuestionDTO question : snapshot.questions()) {
            QuestionAnswerDTO answer = byId.remove(question.id());
            if (answer == null) {
                throw new IllegalArgumentException("every question must be answered exactly once");
            }
            normalized.add(normalizeAnswer(question, answer));
        }
        if (!byId.isEmpty()) {
            throw new IllegalArgumentException("answer contains an unknown question id");
        }
        return new QuestionAnswerResultDTO(normalized);
    }

    /**
     * 解码数据库中已通过准入的完整答案事件。
     *
     * @param payload 答案事件负载
     * @param targetEventId 目标问题事件标识
     * @return 已规范化答案
     * @throws IllegalArgumentException 负载不是已规范化的完整答案事件时抛出
     */
    public QuestionAnswerResultDTO decodeCommittedAnswers(String payload, String targetEventId) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            requireClosedObject(root, ANSWER_EVENT_FIELDS, "question answer event");
            if (!targetEventId.equals(requireText(root.get("targetEventId")))) {
                throw new IllegalArgumentException("question answer target changed after acceptance");
            }
            JsonNode answers = root.get("answers");
            if (answers == null
                    || !answers.isArray()
                    || answers.isEmpty()
                    || answers.size() > ClawConstants.RuntimeApi.MAX_QUESTIONS) {
                throw new IllegalArgumentException("committed question answers are invalid");
            }
            List<QuestionAnswerDTO> result = new ArrayList<>();
            for (JsonNode answer : answers) {
                result.add(decodeAnswer(answer));
            }
            return new QuestionAnswerResultDTO(result);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("committed question answers cannot be decoded", error);
        }
    }

    private static QuestionAnswerDTO decodeAnswer(JsonNode node) {
        requireClosedObject(node, ANSWER_FIELDS, "question answer");
        String id = requireIdentifier(node.get("id"));
        JsonNode selectedNode = node.get("selected");
        if (selectedNode == null || !selectedNode.isArray()) {
            throw new IllegalArgumentException("committed selected labels are invalid");
        }
        List<String> selected = new ArrayList<>();
        selectedNode.forEach(value -> selected.add(requireText(value)));
        String custom = node.has("custom") ? requireText(node.get("custom")) : null;
        return new QuestionAnswerDTO(id, selected, custom);
    }

    private static String requireText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException("committed question answer text is invalid");
        }
        return node.textValue();
    }

    private QuestionDTO parseQuestion(JsonNode node) {
        requireClosedObject(node, QUESTION_FIELDS, "question");
        String id = requireIdentifier(node.get("id"));
        String question = requireDisplay(node.get("question"), ClawConstants.RuntimeApi.MAX_QUESTION_CHARACTERS);
        String header = optionalDisplay(node, "header", ClawConstants.RuntimeApi.MAX_QUESTION_HEADER_CHARACTERS);
        List<QuestionOptionDTO> options = parseOptions(node.get("options"));
        boolean multiSelect = optionalBoolean(node, "multiSelect");
        if (options.isEmpty() && multiSelect) {
            throw invalid("a text question cannot enable multiSelect");
        }
        return new QuestionDTO(id, question, header, options, multiSelect);
    }

    private List<QuestionOptionDTO> parseOptions(JsonNode options) {
        if (options == null) {
            return List.of();
        }
        if (!options.isArray()
                || options.size() < 2
                || options.size() > ClawConstants.RuntimeApi.MAX_QUESTION_OPTIONS) {
            throw invalid("options must contain two to six items");
        }
        List<QuestionOptionDTO> result = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (JsonNode option : options) {
            requireClosedObject(option, OPTION_FIELDS, "question option");
            String label =
                    requireDisplay(option.get("label"), ClawConstants.RuntimeApi.MAX_QUESTION_OPTION_LABEL_CHARACTERS);
            if (!labels.add(label)) {
                throw invalid("normalized option labels must be unique");
            }
            String description = optionalDisplay(
                    option, "description", ClawConstants.RuntimeApi.MAX_QUESTION_OPTION_DESCRIPTION_CHARACTERS);
            result.add(new QuestionOptionDTO(label, description));
        }
        return List.copyOf(result);
    }

    private static Map<String, QuestionAnswerDTO> indexAnswers(List<QuestionAnswerDTO> answers) {
        Map<String, QuestionAnswerDTO> result = new LinkedHashMap<>();
        for (QuestionAnswerDTO answer : answers) {
            if (answer == null || !validIdentifier(answer.id()) || result.putIfAbsent(answer.id(), answer) != null) {
                throw new IllegalArgumentException("answer ids must be valid and unique");
            }
        }
        return result;
    }

    private static QuestionAnswerDTO normalizeAnswer(QuestionDTO question, QuestionAnswerDTO answer) {
        List<String> selected = answer.selected() == null ? null : List.copyOf(answer.selected());
        if (selected == null
                || selected.size() > ClawConstants.RuntimeApi.MAX_QUESTION_OPTIONS
                || selected.stream()
                        .anyMatch(value -> value == null
                                || value.isEmpty()
                                || value.length() > ClawConstants.RuntimeApi.MAX_QUESTION_OPTION_LABEL_CHARACTERS)
                || selected.stream().distinct().count() != selected.size()) {
            throw new IllegalArgumentException("selected labels are invalid");
        }
        String custom = normalizeCustom(answer.custom());
        List<String> ordered = orderSelections(question, selected);
        requireAnswerShape(question, ordered, custom);
        return new QuestionAnswerDTO(question.id(), ordered, custom);
    }

    private static List<String> orderSelections(QuestionDTO question, List<String> selected) {
        Set<String> requested = new HashSet<>(selected);
        List<String> ordered = question.options().stream()
                .map(QuestionOptionDTO::label)
                .filter(requested::remove)
                .toList();
        if (!requested.isEmpty()) {
            throw new IllegalArgumentException("selected label does not belong to the question");
        }
        return ordered;
    }

    private static void requireAnswerShape(QuestionDTO question, List<String> selected, String custom) {
        if (question.options().isEmpty()) {
            if (!selected.isEmpty() || custom == null) {
                throw new IllegalArgumentException("a text question requires only a custom answer");
            }
            return;
        }
        if (!question.multiSelect() && (selected.size() > 1 || (!selected.isEmpty() && custom != null))) {
            throw new IllegalArgumentException("a single-select answer permits one selection or custom text");
        }
        if (selected.isEmpty() && custom == null) {
            throw new IllegalArgumentException("an answer must contain a selection or custom text");
        }
    }

    private static String normalizeCustom(String custom) {
        if (custom == null) {
            return null;
        }
        String normalized = custom.strip();
        if (normalized.isEmpty()
                || normalized.length() > ClawConstants.RuntimeApi.MAX_QUESTION_CUSTOM_ANSWER_CHARACTERS) {
            throw new IllegalArgumentException("custom answer is invalid");
        }
        return normalized;
    }

    private static void requireClosedObject(JsonNode node, Set<String> allowed, String name) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be an object");
        }
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!allowed.containsAll(names) || containsNull(node)) {
            throw invalid(name + " contains unsupported or null fields");
        }
    }

    private static boolean containsNull(JsonNode node) {
        var values = node.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (value.isNull() || (value.isContainerNode() && containsNull(value))) {
                return true;
            }
        }
        return false;
    }

    private static String requireIdentifier(JsonNode value) {
        if (value == null || !value.isTextual() || !validIdentifier(value.textValue())) {
            throw invalid("question id is invalid");
        }
        return value.textValue();
    }

    private static boolean validIdentifier(String value) {
        return value != null
                && value.length() <= ClawConstants.RuntimeApi.MAX_QUESTION_ID_CHARACTERS
                && ClawConstants.RuntimeApi.QUESTION_ID_PATTERN.matcher(value).matches();
    }

    private static String requireDisplay(JsonNode value, int maximum) {
        if (value == null || !value.isTextual()) {
            throw invalid("display text must be a string");
        }
        String normalized = value.textValue().strip();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw invalid("display text is outside the allowed UTF-16 length");
        }
        return normalized;
    }

    private static String optionalDisplay(JsonNode parent, String field, int maximum) {
        return parent.has(field) ? requireDisplay(parent.get(field), maximum) : null;
    }

    private static boolean optionalBoolean(JsonNode parent, String field) {
        if (!parent.has(field)) {
            return false;
        }
        JsonNode value = parent.get(field);
        if (!value.isBoolean()) {
            throw invalid("multiSelect must be a boolean");
        }
        return value.booleanValue();
    }

    private static ToolArgumentsInvalidException invalid(String message) {
        return new ToolArgumentsInvalidException(message);
    }
}
