/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import com.campusclaw.agent.tool.CancellationToken;

import org.junit.jupiter.api.Test;

class AskUserQuestionToolTest {

    @Test
    void shouldRejectMissingAnswerResult() {
        var tool = new AskUserQuestionTool((callId, signal) -> null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> tool.execute("call-1", Map.of(), new CancellationToken(), partial -> {}));

        assertThat(error).hasMessage("User question returned no answer");
    }
}
