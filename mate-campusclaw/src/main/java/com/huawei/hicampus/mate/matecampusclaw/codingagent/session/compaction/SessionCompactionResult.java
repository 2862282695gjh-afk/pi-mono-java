/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.session.compaction;

import java.util.List;
import java.util.Objects;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;

/**
 * 一次上下文压缩产生的摘要、保留消息和用量。
 *
 * @param summary 完整压缩摘要
 * @param retainedMessages 原样保留的最近消息
 * @param compactedMessageCount 被摘要替代的上下文消息数量
 * @param tokensBefore 压缩前估算 Token 数
 * @param estimatedTokensAfter 压缩后估算 Token 数
 * @param usage 生成摘要的模型调用用量
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionCompactionResult(
        String summary,
        List<Message> retainedMessages,
        int compactedMessageCount,
        int tokensBefore,
        int estimatedTokensAfter,
        Usage usage) {
    public SessionCompactionResult {
        summary = Objects.requireNonNull(summary, "summary");
        retainedMessages = List.copyOf(retainedMessages);
        usage = usage == null ? Usage.empty() : usage;
    }

    public int retainedMessageCount() {
        return retainedMessages.size();
    }
}
