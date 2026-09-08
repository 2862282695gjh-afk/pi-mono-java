/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeResultWaitMode;

/**
 * 一批结果补读所需的固定目标、读取方式、最小游标和领取身份。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record ResultWaitTargetDTO(ExecutionTargetDTO target, RuntimeResultWaitMode mode, long afterSeq, long claimId) {}
