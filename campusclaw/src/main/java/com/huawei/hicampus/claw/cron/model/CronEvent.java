/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.cron.model;

import org.springframework.lang.Nullable;

/**
 * Cron 引擎在 Job 生命周期中发出的事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public sealed interface CronEvent {

    @SuppressWarnings("checkstyle:top_class_comment")
    record JobStarted(String jobId, String jobName, String runId) implements CronEvent {}

    @SuppressWarnings("checkstyle:top_class_comment")
    record JobCompleted(String jobId, String jobName, String runId, @Nullable String output) implements CronEvent {}

    @SuppressWarnings("checkstyle:top_class_comment")
    record JobFailed(String jobId, String jobName, String runId, String errorCode) implements CronEvent {}
}
