/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.session.compaction;

/**
 * 公共 Session 对 Host 发布的压缩生命周期事件。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public sealed interface SessionCompactionEvent
        permits SessionCompactionStartedEvent, SessionCompactionCompletedEvent, SessionCompactionFailedEvent {}
