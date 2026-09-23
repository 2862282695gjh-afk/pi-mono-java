/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;

import org.junit.jupiter.api.Test;

class AskUserQuestionToolTest {

    @Test
    void shouldPrepareImmutableQuestionSnapshotForHost() {
        var tool = new AskUserQuestionTool((callId, signal) -> new QuestionAnswerResultDTO(List.of()));
        Map<String, Object> arguments = Map.of(
                "questions",
                List.of(Map.of(
                        "id", "device",
                        "question", "Which device?",
                        "options", List.of(Map.of("label", "AHU"), Map.of("label", "Chiller")))));

        PreparedQuestionInvocation invocation = tool.prepare("call-1", arguments);

        assertThat(invocation.questionRequest().isBusinessConfirmation()).isFalse();
        assertThat(invocation.questionRequest().questions()).singleElement().satisfies(question -> {
            assertThat(question.id()).isEqualTo("device");
            assertThat(question.options()).extracting(QuestionOptionDTO::label).containsExactly("AHU", "Chiller");
        });
        assertThat(invocation.arguments()).isEqualTo(arguments);
    }

    @Test
    void invalidQuestionShouldReturnRecoverableDefinitionBeforeWaiting() {
        var tool = new AskUserQuestionTool((callId, signal) -> {
            throw new AssertionError("invalid question must not wait");
        });
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        Map<String, Object> arguments = Map.of("questions", List.of());

        var result = pipeline.execute(
                tool,
                new ToolCall("call-invalid", tool.name(), arguments),
                arguments,
                new AgentContext(),
                new CancellationToken(),
                null);

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .contains("\"definitionComplete\":true", "\"tool\":\"AskUserQuestion\"");
    }

    @Test
    void shouldRejectMissingAnswerResult() {
        var tool = new AskUserQuestionTool((callId, signal) -> null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> tool.execute("call-1", Map.of(), new CancellationToken(), partial -> {}));

        assertThat(error).hasMessage("User question returned no answer");
    }
}
