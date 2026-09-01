/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.cron.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import org.springframework.lang.Nullable;

/**
 * 单次 Cron Job 执行记录。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record CronRunRecord(
        String runId,
        String jobId,
        long startedAtMs,
        long finishedAtMs,
        RunStatus status,
        @Nullable String errorCode,
        @JsonAlias("error") @Nullable String errorMessage,
        @Nullable String output,
        int turnCount) {

    @SuppressWarnings("checkstyle:top_class_comment")
    public enum RunStatus {
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }
}
