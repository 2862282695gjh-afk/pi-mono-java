/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.CacheRetention;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.ImageContent;
import com.campusclaw.ai.types.InputModality;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.ModelCost;
import com.campusclaw.ai.types.Provider;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingContent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.ToolResultMessage;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;

/**
 * 验证压缩判定、窗口、摘要协议和重复压缩语义与 pi 对齐。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@ExtendWith(MockitoExtension.class)
class SessionCompactorTest {
    @Mock
    private CampusClawAiService aiService;

    @Test
    void retainsOversizedLastAssistantAndSummarizesTurnPrefix() {
        stubSummary("prefix summary", Usage.empty());
        AssistantMessage oversized = assistant("a".repeat(100), Usage.empty(), StopReason.STOP, 2L);
        SessionCompactor compactor = compactor(true, 16_384, 10);

        SessionCompactor.PreparedCompaction prepared =
                compactor.prepare(List.of(new UserMessage("old", 1L), oversized));
        SessionCompactionResult result =
                compactor.compact(prepared, model(), ThinkingLevel.OFF, null).join();

        assertThat(prepared.splitTurn()).isTrue();
        assertThat(result.retainedMessages()).containsExactly(oversized);
        assertThat(result.summary()).contains("Turn Context (split turn)", "prefix summary");
    }

    @Test
    void combinesHistoryAndTurnPrefixUsage() {
        AssistantMessage historySummary = assistant("history", usage(10, 5), StopReason.STOP, 5L);
        AssistantMessage prefixSummary = assistant("prefix", usage(4, 2), StopReason.STOP, 6L);
        when(aiService.completeSimple(any(), any(), any()))
                .thenReturn(Mono.just(historySummary), Mono.just(prefixSummary));
        AssistantMessage oversized = assistant("a".repeat(100), Usage.empty(), StopReason.STOP, 4L);
        List<Message> messages = List.of(
                new UserMessage("old", 1L),
                assistant("old answer", Usage.empty(), StopReason.STOP, 2L),
                new UserMessage("current request", 3L),
                oversized);
        SessionCompactor compactor = compactor(true, 100, 10);

        SessionCompactionResult result = compactor
                .compact(compactor.prepare(messages), model(), ThinkingLevel.OFF, null)
                .join();

        assertThat(result.summary()).contains("history", "prefix", "Turn Context (split turn)");
        assertThat(result.usage()).isEqualTo(usage(14, 7));
        assertThat(result.retainedMessages()).containsExactly(oversized);
        verify(aiService, times(2)).completeSimple(any(), any(), any());
    }

    @Test
    void refusesUnsafeToolResultBoundaryAndAllMessagesInsideWindow() {
        SessionCompactor compactor = compactor(true, 16_384, 10);
        List<Message> unsafe = List.of(
                new UserMessage("task", 1L),
                assistantWithToolCall("notes.txt", 2L),
                new ToolResultMessage("call-1", "Read", List.of(new TextContent("x".repeat(100))), null, false, 3L));

        assertThat(compactor.prepare(unsafe)).isNull();
        assertThat(compactor(true, 16_384, 100).prepare(List.of(new UserMessage("task", 1L), assistant("answer"))))
                .isNull();
    }

    @Test
    void estimatesTokensWithCeilingImagesAndJsonArguments() {
        SessionCompactor compactor = compactor(true, 16_384, 10);
        AssistantMessage content = new AssistantMessage(
                List.of(new ThinkingContent("abc"), new ToolCall("c", "Read", Map.of("path", "a"))),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                2L);

        assertThat(compactor.estimateTokens(List.of(new UserMessage("a", 1L)))).isEqualTo(1);
        assertThat(compactor.estimateTokens(List.of(new UserMessage("", 1L)))).isZero();
        assertThat(compactor.estimateTokens(
                        List.of(new UserMessage(List.of(new ImageContent("ignored", "image/png")), 1L))))
                .isEqualTo(1_200);
        assertThat(compactor.estimateTokens(List.of(content))).isPositive();
    }

    @Test
    void usesAssistantUsageForThresholdAndFallsBackForZeroUsage() {
        SessionCompactor compactor = compactor(true, 100, 10);
        Model small = model(1_000, 200);
        AssistantMessage usageBacked = assistant("short", usage(901, 0), StopReason.STOP, 2L);
        AssistantMessage zeroUsage = assistant("z".repeat(3_604), Usage.empty(), StopReason.STOP, 3L);

        assertThat(compactor.decide(List.of(usageBacked), small, false).action())
                .isEqualTo(AutomaticCompactionDecision.Action.THRESHOLD);
        assertThat(compactor.decide(List.of(zeroUsage), small, false).action())
                .isEqualTo(AutomaticCompactionDecision.Action.THRESHOLD);
    }

    @Test
    void ignoresUsageBeforeLatestCompactionBoundary() {
        SessionCompactor compactor = compactor(true, 100, 10);
        Model small = model(1_000, 200);
        String previous = "old summary";
        List<Message> staleContext = List.of(
                CompactionMessageSupport.summaryMessage(previous, 10L),
                assistant("old", usage(901, 0), StopReason.STOP, 9L),
                assistant("failed", Usage.empty(), StopReason.ERROR, 11L));
        List<Message> currentContext = List.of(
                CompactionMessageSupport.summaryMessage(previous, 10L),
                assistant("current", usage(901, 0), StopReason.STOP, 11L),
                assistant("failed", Usage.empty(), StopReason.ERROR, 12L));

        assertThat(compactor.decide(staleContext, small, false).action())
                .isEqualTo(AutomaticCompactionDecision.Action.NONE);
        assertThat(compactor.decide(currentContext, small, false).action())
                .isEqualTo(AutomaticCompactionDecision.Action.THRESHOLD);
    }

    @Test
    void distinguishesSuccessfulOverflowFromRetryableOverflow() {
        SessionCompactor compactor = compactor(true, 100, 10);
        Model small = model(1_000, 200);
        AssistantMessage successful = assistant("done", usage(1_001, 1), StopReason.STOP, 2L);
        AssistantMessage truncated = assistant("partial", usage(500, 100), StopReason.LENGTH, 3L);

        assertThat(compactor.decide(List.of(successful), small, false).action())
                .isEqualTo(AutomaticCompactionDecision.Action.OVERFLOW_PRESERVE);
        assertThat(compactor.decide(List.of(truncated), small, false).action())
                .isEqualTo(AutomaticCompactionDecision.Action.OVERFLOW_RETRY);
    }

    @Test
    void disablesAutomaticCompactionAndRejectsDifferentModelOverflow() {
        Model small = model(1_000, 200);
        AssistantMessage overflow = assistant("done", usage(1_001, 1), StopReason.STOP, 2L);
        AssistantMessage otherModel = new AssistantMessage(
                overflow.content(),
                overflow.api(),
                overflow.provider(),
                "other",
                null,
                Usage.empty(),
                StopReason.ERROR,
                "prompt is too long",
                2L);

        assertThat(compactor(false, 100, 10)
                        .decide(List.of(overflow), small, false)
                        .action())
                .isEqualTo(AutomaticCompactionDecision.Action.NONE);
        assertThat(compactor(true, 100, 10)
                        .decide(List.of(otherModel), small, false)
                        .action())
                .isEqualTo(AutomaticCompactionDecision.Action.NONE);
    }

    @Test
    void sendsStructuredPromptWithIsolatedSummaryOptions() {
        stubSummary("part onepart two", usage(10, 5));
        SessionCompactor compactor = compactor(true, 100, 1);
        List<Message> messages = List.of(
                new UserMessage("read it", 1L),
                assistantWithToolCall("notes.txt", 2L),
                new ToolResultMessage("call-1", "Read", List.of(new TextContent("x".repeat(2_100))), null, false, 3L),
                new UserMessage("continue", 4L));
        SessionCompactor.PreparedCompaction prepared = compactor.prepare(messages);

        SessionCompactionResult result = compactor
                .compact(prepared, model(), ThinkingLevel.MEDIUM, "focus paths")
                .join();

        ArgumentCaptor<Context> context = ArgumentCaptor.forClass(Context.class);
        ArgumentCaptor<SimpleStreamOptions> options = ArgumentCaptor.forClass(SimpleStreamOptions.class);
        org.mockito.Mockito.verify(aiService).completeSimple(any(), context.capture(), options.capture());
        String prompt = ((TextContent)
                        ((UserMessage) context.getValue().messages().getFirst())
                                .content()
                                .getFirst())
                .text();
        assertThat(prompt)
                .contains("<conversation>", "## Goal", "Additional focus: focus paths", "more characters truncated");
        assertThat(context.getValue().tools()).isNull();
        assertThat(options.getValue().cacheRetention()).isEqualTo(CacheRetention.NONE);
        assertThat(options.getValue().maxTokens()).isEqualTo(80);
        assertThat(options.getValue().sessionId()).isNotBlank();
        assertThat(result.summary()).contains("<read-files>\nnotes.txt\n</read-files>");
    }

    @Test
    void updatesPreviousSummaryAndInheritsReadFiles() {
        stubSummary("updated", Usage.empty());
        SessionCompactor compactor = compactor(true, 100, 1);
        String previous = "old summary\n\n<read-files>\nold.txt\n</read-files>";
        List<Message> messages = List.of(
                CompactionMessageSupport.summaryMessage(previous, 10L),
                new UserMessage("next", 11L),
                assistantWithToolCall("new.txt", 12L),
                new UserMessage("keep", 13L));
        SessionCompactor.PreparedCompaction prepared = compactor.prepare(messages);

        SessionCompactionResult result =
                compactor.compact(prepared, model(), ThinkingLevel.OFF, null).join();

        ArgumentCaptor<Context> context = ArgumentCaptor.forClass(Context.class);
        org.mockito.Mockito.verify(aiService).completeSimple(any(), context.capture(), any());
        String prompt = ((TextContent)
                        ((UserMessage) context.getValue().messages().getFirst())
                                .content()
                                .getFirst())
                .text();
        assertThat(prompt).contains("<previous-summary>", "old summary", "NEW conversation messages");
        assertThat(result.summary()).contains("old.txt", "new.txt");
        assertThat(prepared.compactedMessageCount()).isEqualTo(3);
    }

    @Test
    void rejectsSummaryToolCallsAndCombinesAllTextBlocks() {
        AssistantMessage invalid = new AssistantMessage(
                List.of(new TextContent("text"), new ToolCall("c", "Read", Map.of())),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                Usage.empty(),
                StopReason.STOP,
                null,
                2L);
        when(aiService.completeSimple(any(), any(), any())).thenReturn(Mono.just(invalid));
        SessionCompactor compactor = compactor(true, 100, 1);
        SessionCompactor.PreparedCompaction prepared =
                compactor.prepare(List.of(new UserMessage("old", 1L), new UserMessage("keep", 2L)));

        assertThatThrownBy(() -> compactor
                        .compact(prepared, model(), ThinkingLevel.OFF, null)
                        .join())
                .hasRootCauseMessage("Compaction model attempted to call a tool");
    }

    @Test
    void retriesTransientSummaryFailureWithBoundedPolicy() {
        CompactionProperties properties = new CompactionProperties();
        properties.setReserveTokens(100);
        properties.setKeepRecentTokens(1);
        properties.setSummaryMaxRetries(1);
        properties.setSummaryRetryBaseDelayMs(1L);
        SessionCompactor compactor = new SessionCompactor(aiService, properties);
        AssistantMessage transientFailure = new AssistantMessage(
                List.of(),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                Usage.empty(),
                StopReason.ERROR,
                "503 service unavailable",
                2L);
        when(aiService.completeSimple(any(), any(), any()))
                .thenReturn(Mono.just(transientFailure), Mono.just(assistant("summary")));
        SessionCompactor.PreparedCompaction prepared =
                compactor.prepare(List.of(new UserMessage("old", 1L), new UserMessage("keep", 2L)));

        SessionCompactionResult result =
                compactor.compact(prepared, model(), ThinkingLevel.OFF, null).join();

        assertThat(result.summary()).isEqualTo("summary");
        verify(aiService, times(2)).completeSimple(any(), any(), any());
    }

    @Test
    void doesNotRetryDeterministicSummaryFailure() {
        CompactionProperties properties = new CompactionProperties();
        properties.setKeepRecentTokens(1);
        properties.setSummaryMaxRetries(3);
        properties.setSummaryRetryBaseDelayMs(1L);
        SessionCompactor compactor = new SessionCompactor(aiService, properties);
        AssistantMessage quotaFailure = new AssistantMessage(
                List.of(),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                Usage.empty(),
                StopReason.ERROR,
                "insufficient_quota",
                2L);
        when(aiService.completeSimple(any(), any(), any())).thenReturn(Mono.just(quotaFailure));
        SessionCompactor.PreparedCompaction prepared =
                compactor.prepare(List.of(new UserMessage("old", 1L), new UserMessage("keep", 2L)));

        assertThatThrownBy(() -> compactor
                        .compact(prepared, model(), ThinkingLevel.OFF, null)
                        .join())
                .hasRootCauseMessage("Compaction model call did not complete");
        verify(aiService).completeSimple(any(), any(), any());
    }

    private SessionCompactor compactor(boolean enabled, int reserveTokens, int keepRecentTokens) {
        CompactionProperties properties = new CompactionProperties();
        properties.setEnabled(enabled);
        properties.setReserveTokens(reserveTokens);
        properties.setKeepRecentTokens(keepRecentTokens);
        return new SessionCompactor(aiService, properties);
    }

    private void stubSummary(String text, Usage usage) {
        AssistantMessage summary = new AssistantMessage(
                List.of(
                        new TextContent(text.substring(0, text.length() / 2)),
                        new TextContent(text.substring(text.length() / 2))),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                usage,
                StopReason.STOP,
                null,
                2L);
        when(aiService.completeSimple(any(), any(), any())).thenReturn(Mono.just(summary));
    }

    private static AssistantMessage assistant(String text) {
        return assistant(text, Usage.empty(), StopReason.STOP, 2L);
    }

    private static AssistantMessage assistant(String text, Usage usage, StopReason reason, long timestamp) {
        return new AssistantMessage(
                List.of(new TextContent(text)),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                usage,
                reason,
                null,
                timestamp);
    }

    private static AssistantMessage assistantWithToolCall(String path, long timestamp) {
        return new AssistantMessage(
                List.of(new ToolCall("call-1", "Read", Map.of("path", path))),
                "anthropic-messages",
                "anthropic",
                "test-model",
                null,
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                timestamp);
    }

    private static Usage usage(int input, int output) {
        return new Usage(input, output, 0, 0, input + output, Cost.empty());
    }

    private static Model model() {
        return model(200_000, 4_096);
    }

    private static Model model(int contextWindow, int maxTokens) {
        return new Model(
                "test-model",
                "Test Model",
                Api.ANTHROPIC_MESSAGES,
                Provider.ANTHROPIC,
                "https://example.com",
                true,
                List.of(InputModality.TEXT),
                new ModelCost(1.0, 2.0, 0.5, 0.25),
                contextWindow,
                maxTokens,
                null,
                null,
                null);
    }
}
