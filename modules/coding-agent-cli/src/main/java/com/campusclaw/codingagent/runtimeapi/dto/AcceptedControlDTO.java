/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * 控制事件完成原子受理后的内部结果。
 *
 * @param receipt 已持久化且已分配顺序号的控制事件 Entry
 * @param target 控制请求绑定的固定根执行及当前结果段
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record AcceptedControlDTO(RuntimeEntryDTO receipt, ExecutionTargetDTO target) {}
