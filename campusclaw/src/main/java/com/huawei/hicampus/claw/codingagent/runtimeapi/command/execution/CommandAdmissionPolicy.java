/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;

/**
 * 计算查询或带参操作的预览准入；返回 null 表示允许，修改服务仍须锁内复核。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface CommandAdmissionPolicy {
    String unavailableCode(CommandSessionSnapshotDTO session, boolean withArguments);
}
