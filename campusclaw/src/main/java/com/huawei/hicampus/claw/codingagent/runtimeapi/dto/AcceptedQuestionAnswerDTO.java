/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 用户问题答案事务提交后的回执及续跑目标。
 *
 * @param receipt 回执 Entry
 * @param event 已提交完整事件
 * @param target 新续跑段
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record AcceptedQuestionAnswerDTO(RuntimeEntryDTO receipt, CommittedEventDTO event, ExecutionTargetDTO target) {}
