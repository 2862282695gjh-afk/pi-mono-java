/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.StopReason;
import com.campusclaw.ai.types.Usage;

import org.junit.jupiter.api.Test;

/**
 * 压缩摘要重试错误码分类测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
class CompactionRetryClassifierTest {
    @Test
    void retriesStableTransientCodeWithoutErrorMessage() {
        assertThat(CompactionRetryClassifier.isRetryable(error("MODEL_RATE_LIMITED")))
                .isTrue();
    }

    @Test
    void rejectsStableNonRetryableCodeWithoutParsingText() {
        assertThat(CompactionRetryClassifier.isRetryable(error("INVALID_LLM_CHAT_REQUEST")))
                .isFalse();
    }

    private static AssistantMessage error(String errorCode) {
        return new AssistantMessage(
                List.of(),
                "openai-completions",
                "mate-model-manager",
                "model_test",
                null,
                null,
                Usage.empty(),
                StopReason.ERROR,
                errorCode,
                null,
                0L);
    }
}
