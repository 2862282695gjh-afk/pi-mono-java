/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * 已绑定到固定执行、原始 Assistant Entry 和退出调用的不可变计划候选。
 *
 * @param target 固定执行目标
 * @param assistantEntryId 原始 Assistant Entry 标识
 * @param planEventId 计划预览与完整事件共用的公共标识
 * @param sourceEventId 原始用户消息公共标识
 * @param toolCallId 唯一 ExitPlanMode 调用标识
 * @param content 完整计划正文
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
public record PlanProposalSnapshotDTO(
        ExecutionTargetDTO target,
        String assistantEntryId,
        String planEventId,
        String sourceEventId,
        String toolCallId,
        String content) {}
