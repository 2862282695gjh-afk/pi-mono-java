/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.cron.model;

import org.springframework.lang.Nullable;

/**
 * Runtime state of a cron job, persisted alongside the job definition.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record CronJobState(
        long nextRunAtMs,
        long runningAtMs,
        long lastRunAtMs,
        @Nullable String lastRunStatus,
        int consecutiveErrors,
        int totalRuns) {

    public static CronJobState initial() {
        return new CronJobState(0, 0, 0, null, 0, 0);
    }
}
