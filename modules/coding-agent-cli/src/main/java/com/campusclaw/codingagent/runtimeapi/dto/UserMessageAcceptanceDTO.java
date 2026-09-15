/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * user.message 完成原子受理后的内部结果。
 *
 * @param receipt 已持久化且已分配顺序号的用户消息 Entry
 * @param target 本轮固定执行及初始结果段
 * @param mode 受理事务锁内读取的权威执行模式
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record UserMessageAcceptanceDTO(RuntimeEntryDTO receipt, ExecutionTargetDTO target, String mode) {
    public UserMessageAcceptanceDTO(RuntimeEntryDTO receipt, ExecutionTargetDTO target) {
        this(receipt, target, "build");
    }
}
