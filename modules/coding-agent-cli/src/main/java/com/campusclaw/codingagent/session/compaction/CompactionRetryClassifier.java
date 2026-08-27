/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import java.util.List;
import java.util.regex.Pattern;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;

final class CompactionRetryClassifier {
<<<<<<< HEAD
=======
    private static final List<String> RETRYABLE_CODES = List.of(
            "MODEL_RATE_LIMITED",
            "MODEL_UNAVAILABLE",
            "MANAGER_UNAVAILABLE",
            "MODEL_INVOCATION_TIMEOUT",
            "UPSTREAM_MODEL_ERROR",
            "UPSTREAM_STREAM_ERROR",
            "MATE_MODEL_MANAGER_ERROR");

>>>>>>> upstream/main
    private static final List<Pattern> NON_RETRYABLE = patterns(
            "GoUsageLimitError",
            "FreeUsageLimitError",
            "Monthly usage limit reached",
            "available balance",
            "insufficient_quota",
            "out of budget",
            "quota exceeded",
            "billing");

    private static final List<Pattern> RETRYABLE = patterns(
            "overloaded",
            "rate.?limit",
            "too many requests",
            "(?:429|500|502|503|504|524)",
            "service.?unavailable",
            "server.?error",
            "internal.?error",
            "provider.?returned.?error",
            "network.?error",
            "connection.?error",
            "connection.?refused",
            "connection.?lost",
            "fetch failed",
            "ENOTFOUND",
            "EAI_AGAIN",
            "upstream.?connect",
            "reset before headers",
            "socket hang up",
            "timed? out",
            "timeout",
            "terminated",
            "websocket.?closed",
            "websocket.?error",
            "ended without",
            "stream ended before",
            "http2 request did not get a response",
            "retry delay",
            "you can retry your request",
            "try your request again",
            "please retry your request",
            "ResourceExhausted");

    private CompactionRetryClassifier() {}

    static boolean isRetryable(AssistantMessage message) {
<<<<<<< HEAD
        return message.stopReason() == StopReason.ERROR && isRetryable(message.errorMessage());
=======
        return message.stopReason() == StopReason.ERROR
                && (isRetryableCode(message.errorCode()) || isRetryable(message.errorMessage()));
>>>>>>> upstream/main
    }

    static boolean isRetryable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (isRetryable(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRetryable(String message) {
<<<<<<< HEAD
=======
        if (isRetryableCode(message)) {
            return true;
        }
>>>>>>> upstream/main
        if (message == null
                || NON_RETRYABLE.stream()
                        .anyMatch(pattern -> pattern.matcher(message).find())) {
            return false;
        }
        return RETRYABLE.stream().anyMatch(pattern -> pattern.matcher(message).find());
    }

<<<<<<< HEAD
=======
    private static boolean isRetryableCode(String errorCode) {
        return errorCode != null && RETRYABLE_CODES.contains(errorCode);
    }

>>>>>>> upstream/main
    private static List<Pattern> patterns(String... expressions) {
        return java.util.Arrays.stream(expressions)
                .map(expression -> Pattern.compile(expression, Pattern.CASE_INSENSITIVE))
                .toList();
    }
}
