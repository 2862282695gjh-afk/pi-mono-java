/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 固定到一轮消息执行及其一个 HTTP 结果段的内部目标。
 *
 * @param sessionId Session 标识
 * @param executionId 不公开的根执行标识
 * @param rootEventId 原始 user.message 的公共事件标识
 * @param segmentId 初始执行段或确认续跑段标识
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record ExecutionTargetDTO(String sessionId, String executionId, String rootEventId, String segmentId) {}
