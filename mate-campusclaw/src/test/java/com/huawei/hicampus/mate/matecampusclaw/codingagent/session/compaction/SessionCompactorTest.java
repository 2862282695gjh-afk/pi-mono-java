/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Api;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.InputModality;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ModelCost;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ThinkingLevel;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;

/**
 * 验证压缩窗口与 pi 一致地选择真实且安全的消息边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@ExtendWith(MockitoExtension.class)
class SessionCompactorTest {
    @Mock
    private CampusClawAiService aiService;

    @Test
    void shouldRetainOversizedLastAssistant() {
        stubSummary();
        AssistantMessage oversized = assistant("a".repeat(100));
        List<Message> messages = List.of(new UserMessage("old", 1L), oversized);

        SessionCompactionResult result = compactor(10)
                .compact(messages, model(), ThinkingLevel.OFF, null)
                .join();

        assertThat(result.retainedMessages()).containsExactly(oversized);
        assertThat(result.compactedMessageCount()).isEqualTo(1);
    }

    @Test
    void shouldRefuseCompactionWhenOversizedToolResultHasNoSafeBoundaryAfterIt() {
        List<Message> messages = List.of(
                new UserMessage("task", 1L),
                assistantWithToolCall(),
                new ToolResultMessage("call-1", "Read", List.of(new TextContent("x".repeat(100))), null, false, 3L));

        assertThatThrownBy(() -> compactor(10).compact(messages, model(), ThinkingLevel.OFF, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Session has no compactable history");
    }

    @Test
    void shouldKeepToolCallAndToolResultTogether() {
        stubSummary();
        AssistantMessage toolCall = assistantWithToolCall();
        ToolResultMessage toolResult =
                new ToolResultMessage("call-1", "Read", List.of(new TextContent("ok")), null, false, 5L);
        List<Message> messages = List.of(
                new UserMessage("old".repeat(40), 1L),
                assistant("old answer"),
                new UserMessage("current task", 3L),
                toolCall,
                toolResult);

        SessionCompactionResult result =
                compactor(4).compact(messages, model(), ThinkingLevel.OFF, null).join();

        assertThat(result.retainedMessages()).containsExactly(toolCall, toolResult);
    }

    @Test
    void shouldRefuseCompactionWhenAllMessagesFitRecentWindow() {
        List<Message> messages = List.of(new UserMessage("task", 1L), assistant("answer"));

        assertThatThrownBy(() -> compactor(100).compact(messages, model(), ThinkingLevel.OFF, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Session has no compactable history");
    }

    private SessionCompactor compactor(int keepRecentTokens) {
        CompactionProperties properties = new CompactionProperties();
        properties.setKeepRecentTokens(keepRecentTokens);
        return new SessionCompactor(aiService, properties);
    }

    private void stubSummary() {
        when(aiService.completeSimple(any(), any(), any())).thenReturn(Mono.just(assistant("summary")));
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage(
                List.of(new TextContent(text)),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                Usage.empty(),
                StopReason.STOP,
                null,
                2L);
    }

    private static AssistantMessage assistantWithToolCall() {
        return new AssistantMessage(
                List.of(new ToolCall("call-1", "Read", Map.of("path", "notes.txt"))),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                4L);
    }

    private static Model model() {
        return new Model(
                "test-model",
                "Test Model",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(1.0, 2.0, 0.5, 0.25),
                200_000,
                4_096,
                null,
                null,
                null);
    }
}
