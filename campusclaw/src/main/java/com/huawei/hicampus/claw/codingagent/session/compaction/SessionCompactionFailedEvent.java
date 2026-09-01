/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session.compaction;

/**
 * 表示一次 Session 压缩失败且原上下文保持不变。
 *
 * @param reason 压缩原因
 * @param willRetry 是否原计划在完成后重试模型调用
 * @param aborted 是否由取消触发
 * @param message 已脱敏的错误摘要
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionCompactionFailedEvent(CompactionReason reason, boolean willRetry, boolean aborted, String message)
        implements SessionCompactionEvent {
    public SessionCompactionFailedEvent(CompactionReason reason, boolean willRetry, String message) {
        this(reason, willRetry, false, message);
    }
}
