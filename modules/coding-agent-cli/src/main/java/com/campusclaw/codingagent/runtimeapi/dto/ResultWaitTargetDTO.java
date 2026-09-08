/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import com.campusclaw.codingagent.runtimeapi.event.RuntimeResultWaitMode;

/**
 * 一批结果补读所需的固定目标、读取方式和最小游标。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record ResultWaitTargetDTO(ExecutionTargetDTO target, RuntimeResultWaitMode mode, long afterSeq) {}
