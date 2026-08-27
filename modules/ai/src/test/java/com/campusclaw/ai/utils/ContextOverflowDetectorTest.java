/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Cost;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.Usage;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ContextOverflowDetectorTest {

    private static AssistantMessage error(String errorMessage) {
        return new AssistantMessage(
                List.of(new TextContent("", null)),
                "x",
                "x",
                "x",
                null,
                Usage.empty(),
                StopReason.ERROR,
                errorMessage,
                0L);
    }

    private static AssistantMessage stopped(int input, int cacheRead) {
        Usage u = new Usage(input, 0, cacheRead, 0, input + cacheRead, Cost.empty());
        return new AssistantMessage(
                List.of(new TextContent("ok", null)), "x", "x", "x", null, u, StopReason.STOP, null, 0L);
    }

    private static AssistantMessage errorCode(String errorCode) {
        return new AssistantMessage(
                List.of(), "x", "x", "x", null, null, Usage.empty(), StopReason.ERROR, errorCode, null, 0L);
    }

    private static AssistantMessage lengthStopped(int input, int output) {
        Usage usage = new Usage(input, output, 0, 0, input + output, Cost.empty());
        return new AssistantMessage(
                List.of(new TextContent("partial", null)), "x", "x", "x", null, usage, StopReason.LENGTH, null, 0L);
    }

    @Nested
    class ErrorPatterns {

        @Test
        void anthropicPromptTooLong() {
            assertTrue(ContextOverflowDetector.isContextOverflow(error("prompt is too long: 200000 tokens")));
        }

        @Test
        void stableCodeTakesPrecedenceOverMissingErrorMessage() {
            assertTrue(ContextOverflowDetector.isContextOverflow(errorCode("CONTEXT_WINDOW_EXCEEDED")));
        }

        @Test
        void openAIExceedsContextWindow() {
            assertTrue(ContextOverflowDetector.isContextOverflow(error("This model exceeds the context window")));
        }

        @Test
        void bedrockInputTooLong() {
            assertTrue(ContextOverflowDetector.isContextOverflow(error("Input is too long for requested model")));
        }

        @Test
        void googleInputTokenExceeds() {
            assertTrue(ContextOverflowDetector.isContextOverflow(
                    error("Your input token count of 500000 exceeds the maximum allowed")));
        }

        @Test
        void zaiSilentOverflowDetected() {
            AssistantMessage msg = stopped(10000, 0);
            assertTrue(ContextOverflowDetector.isContextOverflow(msg, 8000));
        }

        @Test
        void zaiPattern() {
            assertTrue(ContextOverflowDetector.isContextOverflow(error("model_context_window_exceeded")));
        }

        @Test
        void mistralPattern() {
            assertTrue(ContextOverflowDetector.isContextOverflow(
                    error("too large for model with 8192 maximum context length")));
        }

        @Test
        void cerebrasBareStatus() {
            assertTrue(ContextOverflowDetector.isContextOverflow(error("400 (no body)")));
            assertTrue(ContextOverflowDetector.isContextOverflow(error("413 status code (no body)")));
        }

        @Test
        void genericTokenLimit() {
            assertTrue(ContextOverflowDetector.isContextOverflow(error("token limit exceeded")));
            assertTrue(ContextOverflowDetector.isContextOverflow(error("Too many tokens")));
        }

        @Test
        void additionalPiProviderPatterns() {
            assertTrue(ContextOverflowDetector.isContextOverflow(error("request_too_large")));
            assertTrue(ContextOverflowDetector.isContextOverflow(
                    error("Input length exceeds the maximum allowed input length of 32,000 tokens")));
            assertTrue(ContextOverflowDetector.isContextOverflow(
                    error("Prompt has 9,000 tokens, but the configured context size is 8,192 tokens")));
            assertTrue(ContextOverflowDetector.isContextOverflow(error("Range of input length should be [1, 100000]")));
        }

        @Test
        void xiaomiLengthOverflowDetected() {
            assertTrue(ContextOverflowDetector.isContextOverflow(lengthStopped(9_900, 0), 10_000));
            assertFalse(ContextOverflowDetector.isContextOverflow(lengthStopped(9_800, 0), 10_000));
        }

        @Test
        void lengthBelowDesiredOutputIsRecoverable() {
            assertTrue(ContextOverflowDetector.isRecoverableLength(lengthStopped(1_000, 100), 200));
            assertFalse(ContextOverflowDetector.isRecoverableLength(lengthStopped(1_000, 200), 200));
        }
    }

    @Nested
    class NonOverflow {

        @Test
        void otherErrorNotOverflow() {
            assertFalse(ContextOverflowDetector.isContextOverflow(error("Rate limit exceeded")));
            assertFalse(ContextOverflowDetector.isContextOverflow(
                    error("Throttling error: Too many tokens, please retry")));
            assertFalse(ContextOverflowDetector.isContextOverflow(error("Too many requests: too many tokens")));
        }

        @Test
        void nullErrorNotOverflow() {
            assertFalse(ContextOverflowDetector.isContextOverflow(error(null)));
        }

        @Test
        void successWithinWindowNotOverflow() {
            assertFalse(ContextOverflowDetector.isContextOverflow(stopped(1000, 0), 8000));
        }

        @Test
        void successWithoutContextWindow() {
            assertFalse(ContextOverflowDetector.isContextOverflow(stopped(1000, 0), 0));
        }

        @Test
        void noArgConvenienceDefaultsToNoSilentCheck() {
            assertFalse(ContextOverflowDetector.isContextOverflow(stopped(99999, 0)));
        }
    }
}
