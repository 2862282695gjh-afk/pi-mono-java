/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.ai.types.TextContent;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 问题 Schema、UTF-16 边界和答案交叉字段测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
class QuestionRequestParserTest {
    private final ObjectMapper json = new ObjectMapper();

    private final QuestionRequestParser parser = new QuestionRequestParser(json);

    @Test
    void stripsDisplayTextAndUsesUtf16Length() {
        var snapshot = parser.parseArguments(Map.of(
                "questions",
                List.of(Map.of(
                        "id", "Case_1",
                        "question", "  请选择  ",
                        "options", List.of(Map.of("label", " A "), Map.of("label", "B"))))));

        assertThat(snapshot.questions().getFirst().question()).isEqualTo("请选择");
        assertThat(snapshot.questions().getFirst().options())
                .extracting(QuestionOptionDTO::label)
                .containsExactly("A", "B");
        String overLimit = "😀".repeat(1025);
        assertThatThrownBy(() ->
                        parser.parseArguments(Map.of("questions", List.of(Map.of("id", "q", "question", overLimit)))))
                .isInstanceOf(ToolArgumentsInvalidException.class);
    }

    @Test
    void rejectsUnknownNullAndDuplicateNormalizedFields() {
        assertThatThrownBy(() -> parser.parseArguments(
                        Map.of("questions", List.of(Map.of("id", "q", "question", "Q", "unknown", true)))))
                .isInstanceOf(ToolArgumentsInvalidException.class);
        assertThatThrownBy(() -> parser.parseArguments(Map.of(
                        "questions",
                        List.of(Map.of(
                                "id",
                                "q",
                                "question",
                                "Q",
                                "options",
                                List.of(Map.of("label", " A "), Map.of("label", "A")))))))
                .isInstanceOf(ToolArgumentsInvalidException.class);
        var withNull = new java.util.LinkedHashMap<String, Object>();
        withNull.put("id", "q");
        withNull.put("question", "Q");
        withNull.put("header", null);
        assertThatThrownBy(() -> parser.parseArguments(Map.of("questions", List.of(withNull))))
                .isInstanceOf(ToolArgumentsInvalidException.class);
    }

    @Test
    void normalizesAnswersByQuestionAndOriginalOptionOrder() {
        var snapshot = parser.parseArguments(Map.of(
                "questions",
                List.of(
                        Map.of(
                                "id",
                                "multi",
                                "question",
                                "Choose",
                                "options",
                                List.of(Map.of("label", "A"), Map.of("label", "B")),
                                "multiSelect",
                                true),
                        Map.of("id", "text", "question", "Why"))));

        var result = parser.normalizeAnswers(
                snapshot,
                List.of(
                        new QuestionAnswerDTO("text", List.of(), "  原因  "),
                        new QuestionAnswerDTO("multi", List.of("B", "A"), null)));

        assertThat(result.answers())
                .containsExactly(
                        new QuestionAnswerDTO("multi", List.of("A", "B"), null),
                        new QuestionAnswerDTO("text", List.of(), "原因"));
    }

    @Test
    void enforcesSingleMultiAndTextAnswerShapes() {
        var single = parser.parseArguments(Map.of(
                "questions",
                List.of(Map.of(
                        "id", "q", "question", "Q", "options", List.of(Map.of("label", "A"), Map.of("label", "B"))))));
        assertThatThrownBy(() ->
                        parser.normalizeAnswers(single, List.of(new QuestionAnswerDTO("q", List.of("A"), "custom"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        parser.normalizeAnswers(single, List.of(new QuestionAnswerDTO("q", List.of("A", "B"), null))))
                .isInstanceOf(IllegalArgumentException.class);
        var text = parser.parseArguments(Map.of("questions", List.of(Map.of("id", "q", "question", "Q"))));
        assertThatThrownBy(() -> parser.normalizeAnswers(text, List.of(new QuestionAnswerDTO("q", List.of(), "  "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolReturnsOneCanonicalJsonTextBlockWithoutNullCustom() throws Exception {
        QuestionAnswerResultDTO answer =
                new QuestionAnswerResultDTO(List.of(new QuestionAnswerDTO("q", List.of("A"), null)));
        var tool = new AskUserQuestionTool((callId, signal) -> answer);

        var result = tool.execute("call-1", Map.of(), new CancellationToken(), partial -> {});

        assertThat(result.content()).singleElement().isInstanceOf(TextContent.class);
        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(json.readTree(text)).isEqualTo(json.readTree("{\"answers\":[{\"id\":\"q\",\"selected\":[\"A\"]}]}"));
        assertThat(tool.executionMode().name()).isEqualTo("SEQUENTIAL");
        assertThat(tool.parameters().path("additionalProperties").booleanValue())
                .isFalse();
        assertThat(tool.parameters()
                        .path("properties")
                        .path("questions")
                        .path("maxItems")
                        .intValue())
                .isEqualTo(3);
    }
}
