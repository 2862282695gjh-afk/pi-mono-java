/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import com.campusclaw.ai.CampusClawAiService;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.CacheRetention;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ThinkingLevel;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.ai.types.Usage;
import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.ai.utils.ContextOverflowDetector;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 为公共 Agent Session 计算压缩时机、生成摘要并选择安全保留窗口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class SessionCompactor {
    private final CampusClawAiService aiService;

    private final CompactionProperties properties;

    public SessionCompactor(CampusClawAiService aiService, CompactionProperties properties) {
        this.aiService = aiService;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public AutomaticCompactionDecision decide(List<Message> messages, Model model, boolean includeAborted) {
        AssistantMessage assistant = lastAssistant(messages);
        if (!properties.isEnabled() || assistant == null) {
            return AutomaticCompactionDecision.none(assistant);
        }
        if (!includeAborted && assistant.stopReason() == StopReason.ABORTED) {
            return AutomaticCompactionDecision.none(assistant);
        }
        long boundary = CompactionMessageSupport.latestBoundaryTimestamp(messages);
        if (boundary >= 0 && assistant.timestamp() <= boundary) {
            return AutomaticCompactionDecision.none(assistant);
        }
        AutomaticCompactionDecision overflow = overflowDecision(assistant, model);
        if (overflow.requiresCompaction()) {
            return overflow;
        }
        return thresholdDecision(messages, model, assistant, boundary);
    }

    public PreparedCompaction prepare(List<Message> messages) {
        int boundaryStart = CompactionMessageSupport.boundaryStart(messages);
        int splitIndex = findSplitIndex(messages, boundaryStart);
        if (splitIndex <= boundaryStart) {
            return null;
        }
        int turnStart = findTurnStart(messages, splitIndex, boundaryStart);
        boolean splitTurn = messages.get(splitIndex) instanceof AssistantMessage && turnStart >= boundaryStart;
        int historyEnd = splitTurn ? turnStart : splitIndex;
        String previousSummary = CompactionMessageSupport.previousSummary(messages);
        List<Message> history = List.copyOf(messages.subList(boundaryStart, historyEnd));
        List<Message> turnPrefix = splitTurn ? List.copyOf(messages.subList(turnStart, splitIndex)) : List.of();
        if (history.isEmpty() && turnPrefix.isEmpty()) {
            return null;
        }
        Set<String> files = trackedFiles(previousSummary, history, turnPrefix);
        return new PreparedCompaction(
                history,
                turnPrefix,
                List.copyOf(messages.subList(splitIndex, messages.size())),
                CompactionTokenEstimator.estimateContextTokens(messages).tokens(),
                previousSummary,
                files,
                splitIndex,
                splitTurn);
    }

    public CompletableFuture<SessionCompactionResult> compact(
            PreparedCompaction prepared, Model model, ThinkingLevel thinking, String customInstructions) {
        if (prepared.splitTurn()) {
            return compactSplitTurn(prepared, model, thinking, customInstructions);
        }
        return summarizeHistory(
                        prepared.historyMessages(), prepared.previousSummary(), model, thinking, customInstructions)
                .thenApply(summary -> result(prepared, summary.text(), summary.usage()));
    }

    public int estimateTokens(List<Message> messages) {
        return CompactionTokenEstimator.estimateMessages(messages);
    }

    public List<Message> compactedMessages(SessionCompactionResult result) {
        List<Message> messages = new ArrayList<>();
        messages.add(CompactionMessageSupport.summaryMessage(result.summary(), System.currentTimeMillis()));
        messages.addAll(result.retainedMessages());
        return List.copyOf(messages);
    }

    private AutomaticCompactionDecision overflowDecision(AssistantMessage assistant, Model model) {
        if (!sameModel(assistant, model)) {
            return AutomaticCompactionDecision.none(assistant);
        }
        boolean overflow = ContextOverflowDetector.isContextOverflow(assistant, model.contextWindow());
        boolean recoverable = ContextOverflowDetector.isRecoverableLength(assistant, model.maxTokens());
        if (!overflow && !recoverable) {
            return AutomaticCompactionDecision.none(assistant);
        }
        AutomaticCompactionDecision.Action action = assistant.stopReason() == StopReason.STOP
                ? AutomaticCompactionDecision.Action.OVERFLOW_PRESERVE
                : AutomaticCompactionDecision.Action.OVERFLOW_RETRY;
        return new AutomaticCompactionDecision(action, assistant);
    }

    private AutomaticCompactionDecision thresholdDecision(
            List<Message> messages, Model model, AssistantMessage assistant, long boundary) {
        int directTokens = CompactionTokenEstimator.calculateContextTokens(assistant.usage());
        if (assistant.stopReason() != StopReason.ERROR && directTokens > 0) {
            return thresholdResult(directTokens, model, assistant);
        }
        CompactionTokenEstimator.ContextUsageEstimate estimate =
                CompactionTokenEstimator.estimateContextTokens(messages);
        if (isStaleUsage(messages, estimate.lastUsageIndex(), boundary)) {
            return AutomaticCompactionDecision.none(assistant);
        }
        return thresholdResult(estimate.tokens(), model, assistant);
    }

    private AutomaticCompactionDecision thresholdResult(int tokens, Model model, AssistantMessage assistant) {
        int threshold = model.contextWindow() - properties.getReserveTokens();
        AutomaticCompactionDecision.Action action = tokens > threshold
                ? AutomaticCompactionDecision.Action.THRESHOLD
                : AutomaticCompactionDecision.Action.NONE;
        return new AutomaticCompactionDecision(action, assistant);
    }

    private static boolean isStaleUsage(List<Message> messages, int usageIndex, long boundary) {
        if (boundary < 0 || usageIndex < 0) {
            return false;
        }
        return messages.get(usageIndex) instanceof AssistantMessage assistant && assistant.timestamp() <= boundary;
    }

    private CompletableFuture<SessionCompactionResult> compactSplitTurn(
            PreparedCompaction prepared, Model model, ThinkingLevel thinking, String customInstructions) {
        CompletableFuture<SummaryResponse> history = prepared.historyMessages().isEmpty()
                ? CompletableFuture.completedFuture(new SummaryResponse("No prior history.", Usage.empty()))
                : summarizeHistory(
                        prepared.historyMessages(), prepared.previousSummary(), model, thinking, customInstructions);
        return history.thenCompose(historySummary -> summarizeTurnPrefix(prepared.turnPrefixMessages(), model, thinking)
                .thenApply(prefix -> mergeSplit(prepared, historySummary, prefix)));
    }

    private SessionCompactionResult mergeSplit(
            PreparedCompaction prepared, SummaryResponse history, SummaryResponse prefix) {
        String summary = history.text() + "\n\n---\n\n**Turn Context (split turn):**\n\n" + prefix.text();
        return result(prepared, summary, combineUsage(history.usage(), prefix.usage()));
    }

    private CompletableFuture<SummaryResponse> summarizeHistory(
            List<Message> messages,
            String previousSummary,
            Model model,
            ThinkingLevel thinking,
            String customInstructions) {
        String prompt = CompactionPromptBuilder.historyPrompt(messages, previousSummary, customInstructions);
        int maxTokens = summaryMaxTokens(model, 0.8);
        return completeSummary(prompt, model, thinking, maxTokens);
    }

    private CompletableFuture<SummaryResponse> summarizeTurnPrefix(
            List<Message> messages, Model model, ThinkingLevel thinking) {
        String prompt = CompactionPromptBuilder.turnPrefixPrompt(messages);
        int maxTokens = summaryMaxTokens(model, 0.5);
        return completeSummary(prompt, model, thinking, maxTokens);
    }

    private CompletableFuture<SummaryResponse> completeSummary(
            String prompt, Model model, ThinkingLevel thinking, int maxTokens) {
        Context context = new Context(
                CompactionPromptBuilder.SYSTEM_PROMPT,
                List.of(new UserMessage(prompt, System.currentTimeMillis())),
                null);
        SimpleStreamOptions options = summaryOptions(model, thinking, maxTokens);
        Mono<AssistantMessage> call = Mono.defer(() -> aiService.completeSimple(model, context, options))
                .flatMap(SessionCompactor::retryableSummaryResponse);
        return withSummaryRetry(call).map(SessionCompactor::summaryResponse).toFuture();
    }

    private Mono<AssistantMessage> withSummaryRetry(Mono<AssistantMessage> call) {
        if (!properties.isSummaryRetryEnabled() || properties.getSummaryMaxRetries() == 0) {
            return call;
        }
        Retry retry = Retry.backoff(
                        properties.getSummaryMaxRetries(), Duration.ofMillis(properties.getSummaryRetryBaseDelayMs()))
                .jitter(0.0)
                .filter(CompactionRetryClassifier::isRetryable);
        return call.retryWhen(retry);
    }

    private static Mono<AssistantMessage> retryableSummaryResponse(AssistantMessage response) {
        if (!CompactionRetryClassifier.isRetryable(response)) {
            return Mono.just(response);
        }
        String message = response.errorMessage() == null ? "Transient summary failure" : response.errorMessage();
        return Mono.error(new IllegalStateException(message));
    }

    private static SimpleStreamOptions summaryOptions(Model model, ThinkingLevel thinking, int maxTokens) {
        SimpleStreamOptions.Builder builder = SimpleStreamOptions.builder()
                .maxTokens(maxTokens)
                .cacheRetention(CacheRetention.NONE)
                .sessionId(UUID.randomUUID().toString());
        if (model.reasoning() && thinking != null && thinking != ThinkingLevel.OFF) {
            builder.reasoning(thinking);
        }
        return builder.build();
    }

    private int summaryMaxTokens(Model model, double reserveRatio) {
        int reserveLimit = Math.max(1, (int) Math.floor(reserveRatio * properties.getReserveTokens()));
        return model.maxTokens() > 0 ? Math.min(reserveLimit, model.maxTokens()) : reserveLimit;
    }

    private SessionCompactionResult result(PreparedCompaction prepared, String summary, Usage usage) {
        String summaryWithFiles = CompactionMessageSupport.appendReadFiles(summary, prepared.readFiles());
        List<Message> compacted = new ArrayList<>();
        compacted.add(CompactionMessageSupport.summaryMessage(summaryWithFiles, System.currentTimeMillis()));
        compacted.addAll(prepared.retainedMessages());
        return new SessionCompactionResult(
                summaryWithFiles,
                prepared.retainedMessages(),
                prepared.compactedMessageCount(),
                prepared.tokensBefore(),
                estimateTokens(compacted),
                usage);
    }

    private int findSplitIndex(List<Message> messages, int startIndex) {
        List<Integer> cutPoints = findCutPoints(messages, startIndex);
        if (cutPoints.isEmpty()) {
            return startIndex;
        }
        int accumulated = 0;
        int splitIndex = cutPoints.getFirst();
        for (int index = messages.size() - 1; index >= startIndex; index--) {
            accumulated += CompactionTokenEstimator.estimateMessage(messages.get(index));
            if (accumulated >= properties.getKeepRecentTokens()) {
                splitIndex = cutPointAtOrAfter(cutPoints, index);
                break;
            }
        }
        return splitIndex;
    }

    private static List<Integer> findCutPoints(List<Message> messages, int startIndex) {
        List<Integer> points = new ArrayList<>();
        for (int index = startIndex; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage || messages.get(index) instanceof AssistantMessage) {
                points.add(index);
            }
        }
        return points;
    }

    private static int cutPointAtOrAfter(List<Integer> cutPoints, int messageIndex) {
        for (int cutPoint : cutPoints) {
            if (cutPoint >= messageIndex) {
                return cutPoint;
            }
        }
        return cutPoints.getFirst();
    }

    private static int findTurnStart(List<Message> messages, int splitIndex, int startIndex) {
        for (int index = splitIndex; index >= startIndex; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return -1;
    }

    private static Set<String> trackedFiles(String previousSummary, List<Message> history, List<Message> turnPrefix) {
        Set<String> files = new TreeSet<>(CompactionMessageSupport.readFiles(previousSummary));
        files.addAll(FileOperationTracker.filesRead(history));
        files.addAll(FileOperationTracker.filesRead(turnPrefix));
        return java.util.Collections.unmodifiableSet(files);
    }

    private static SummaryResponse summaryResponse(AssistantMessage response) {
        if (response.stopReason() == StopReason.ABORTED) {
            throw new CancellationException("Compaction model call aborted");
        }
        if (response.stopReason() == StopReason.ERROR) {
            throw new IllegalStateException("Compaction model call did not complete");
        }
        if (response.content().stream().anyMatch(ToolCall.class::isInstance)) {
            throw new IllegalStateException("Compaction model attempted to call a tool");
        }
        String text = response.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
        if (text.isBlank()) {
            throw new IllegalStateException("Compaction model returned no summary");
        }
        return new SummaryResponse(text, response.usage() == null ? Usage.empty() : response.usage());
    }

    private static Usage combineUsage(Usage left, Usage right) {
        Cost cost = new Cost(
                left.cost().input() + right.cost().input(),
                left.cost().output() + right.cost().output(),
                left.cost().cacheRead() + right.cost().cacheRead(),
                left.cost().cacheWrite() + right.cost().cacheWrite(),
                left.cost().total() + right.cost().total());
        return new Usage(
                left.input() + right.input(),
                left.output() + right.output(),
                left.cacheRead() + right.cacheRead(),
                left.cacheWrite() + right.cacheWrite(),
                left.totalTokens() + right.totalTokens(),
                cost);
    }

    private static boolean sameModel(AssistantMessage assistant, Model model) {
        return model.provider().value().equals(assistant.provider())
                && model.id().equals(assistant.model());
    }

    private static AssistantMessage lastAssistant(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage assistant) {
                return assistant;
            }
        }
        return null;
    }

    /**
     * 保存一次压缩已经完成的边界计算，供摘要调用与状态替换共同使用。
     *
     * @param historyMessages 需要纳入历史摘要的消息
     * @param turnPrefixMessages 切分当前轮次时需要单独摘要的前缀消息
     * @param retainedMessages 原样保留的后缀消息
     * @param tokensBefore 压缩前上下文 Token
     * @param previousSummary 上一次压缩摘要
     * @param readFiles 已读取文件的累计集合
     * @param compactedMessageCount 当前模型上下文中第一条保留消息之前的消息数
     * @param splitTurn 是否切分了一个轮次
     */
    public record PreparedCompaction(
            List<Message> historyMessages,
            List<Message> turnPrefixMessages,
            List<Message> retainedMessages,
            int tokensBefore,
            String previousSummary,
            Set<String> readFiles,
            int compactedMessageCount,
            boolean splitTurn) {}

    private record SummaryResponse(String text, Usage usage) {}
}
