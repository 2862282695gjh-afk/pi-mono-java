/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.utils;

import java.util.List;
import java.util.regex.Pattern;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;

/**
 * 检测模型供应商返回的上下文溢出及可恢复截断响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ContextOverflowDetector {
    private static final int SILENT_OVERFLOW_PERCENT = 99;

    private static final List<Pattern> OVERFLOW_PATTERNS = List.of(
            pattern("prompt is too long"),
            pattern("request_too_large"),
            pattern("input is too long for requested model"),
            pattern("exceeds the context window"),
            pattern("exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))"),
            pattern("input token count.*exceeds the maximum"),
            pattern("maximum prompt length is \\d+"),
            pattern("reduce the length of the messages"),
            pattern("maximum context length is \\d+ tokens"),
            pattern("exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?"),
            pattern("input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)"),
            pattern("exceeds the limit of \\d+"),
            pattern("exceeds the available context size"),
            pattern("greater than the context length"),
            pattern("context window exceeds limit"),
            pattern("exceeded model token limit"),
            pattern("too large for model with \\d+ maximum context length"),
            pattern("prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?"),
            pattern("model_context_window_exceeded"),
            pattern("prompt too long; exceeded (?:max )?context length"),
            pattern("range of input length should be"),
            pattern("context[_ ]length[_ ]exceeded"),
            pattern("too many tokens"),
            pattern("token limit exceeded"),
            pattern("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)"));

    private static final List<Pattern> NON_OVERFLOW_PATTERNS = List.of(
            pattern("^(Throttling error|Service unavailable):"), pattern("rate limit"), pattern("too many requests"));

    private ContextOverflowDetector() {}

    /**
     * 判断响应是否表示上下文溢出。
     *
     * @param message 待检查的 Assistant 响应
     * @param contextWindow 当前模型上下文窗口；零表示不检查 Usage 信号
     * @return 显式、静默或 Xiaomi length 溢出时返回 {@code true}
     */
    public static boolean isContextOverflow(AssistantMessage message, int contextWindow) {
        if (hasOverflowError(message)) {
            return true;
        }
        if (contextWindow <= 0 || message.usage() == null) {
            return false;
        }
        int inputTokens = message.usage().input() + message.usage().cacheRead();
        if (message.stopReason() == StopReason.STOP) {
            return inputTokens > contextWindow;
        }
        return message.stopReason() == StopReason.LENGTH
                && message.usage().output() == 0
                && (long) inputTokens * 100L >= (long) contextWindow * SILENT_OVERFLOW_PERCENT;
    }

    /**
     * 只检查响应中的显式溢出信号。
     *
     * @param message 待检查的 Assistant 响应
     * @return 错误文本命中溢出信号时返回 {@code true}
     */
    public static boolean isContextOverflow(AssistantMessage message) {
        return isContextOverflow(message, 0);
    }

    /**
     * 判断 length 响应是否在目标输出上限之前结束。
     *
     * @param message 待检查的 Assistant 响应
     * @param desiredMaxOutput 上下文裁剪前的模型原始输出上限
     * @return 允许执行一次压缩后重试时返回 {@code true}
     */
    public static boolean isRecoverableLength(AssistantMessage message, int desiredMaxOutput) {
        return message.stopReason() == StopReason.LENGTH
                && desiredMaxOutput > 0
                && message.usage() != null
                && message.usage().output() < desiredMaxOutput;
    }

    private static boolean hasOverflowError(AssistantMessage message) {
        if (message.stopReason() != StopReason.ERROR) {
            return false;
        }
        if ("CONTEXT_WINDOW_EXCEEDED".equals(message.errorCode())) {
            return true;
        }
        if (message.errorMessage() == null) {
            return false;
        }
        String error = message.errorMessage();
        if (NON_OVERFLOW_PATTERNS.stream()
                .anyMatch(candidate -> candidate.matcher(error).find())) {
            return false;
        }
        return OVERFLOW_PATTERNS.stream()
                .anyMatch(candidate -> candidate.matcher(error).find());
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
    }
}
