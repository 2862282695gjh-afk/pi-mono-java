/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 已原子提交的工具调用和 confirming idle 事件身份。
 *
 * @param toolCallEventId 工具调用完整事件标识
 * @param toolCallEventSeq 工具调用完整事件顺序号
 * @param idleEventId confirming idle 完整事件标识
 * @param idleEventSeq confirming idle 完整事件顺序号
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record ConfirmingEventsDTO(
        String toolCallEventId, long toolCallEventSeq, String idleEventId, long idleEventSeq) {}
