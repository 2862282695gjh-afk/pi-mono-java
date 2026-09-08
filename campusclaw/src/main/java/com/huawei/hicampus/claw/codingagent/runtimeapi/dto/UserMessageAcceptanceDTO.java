/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * user.message 完成原子受理后的内部结果。
 *
 * @param receipt 已持久化且已分配顺序号的用户消息 Entry
 * @param target 本轮固定执行及初始结果段
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record UserMessageAcceptanceDTO(RuntimeEntryDTO receipt, ExecutionTargetDTO target) {}
