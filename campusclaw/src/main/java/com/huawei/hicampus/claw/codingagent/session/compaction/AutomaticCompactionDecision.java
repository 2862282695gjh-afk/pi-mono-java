/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session.compaction;

import com.huawei.hicampus.claw.ai.types.AssistantMessage;

/**
 * 表示一次模型执行结束后的自动压缩判定。
 *
 * @param action 需要执行的动作
 * @param assistant 产生本次判定的最后一条 Assistant 消息
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record AutomaticCompactionDecision(Action action, AssistantMessage assistant) {
    /**
     * 定义自动压缩状态机可采取的动作。
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/24]
     * @since [br_eCampusCore 26.0.0]
     */
    public enum Action {
        NONE,
        THRESHOLD,
        OVERFLOW_PRESERVE,
        OVERFLOW_RETRY
    }

    public static AutomaticCompactionDecision none(AssistantMessage assistant) {
        return new AutomaticCompactionDecision(Action.NONE, assistant);
    }

    public boolean requiresCompaction() {
        return action != Action.NONE;
    }

    public boolean willRetry() {
        return action == Action.OVERFLOW_RETRY;
    }
}
