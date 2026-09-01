/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.annotation.Nullable;

/**
 * A thinking/reasoning content block produced during extended thinking.
 *
 * @param thinking          the thinking text
 * @param thinkingSignature optional signature for the thinking block
 * @param redacted          whether the thinking content has been redacted
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record ThinkingContent(
        @JsonProperty("thinking") String thinking,
        @JsonProperty("thinkingSignature") @Nullable String thinkingSignature,
        @JsonProperty("redacted") boolean redacted)
        implements ContentBlock {

    public ThinkingContent(String thinking) {
        this(thinking, null, false);
    }
}
