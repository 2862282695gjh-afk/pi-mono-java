/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.session.compaction;

/**
 * 表示一次 Session 压缩已经完成。
 *
 * @param reason 压缩原因
 * @param result 压缩结果
 * @param willRetry 完成后是否重试被中断的模型调用
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionCompactionCompletedEvent(
        CompactionReason reason, SessionCompactionResult result, boolean willRetry) implements SessionCompactionEvent {}
