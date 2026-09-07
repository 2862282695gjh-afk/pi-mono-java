/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command.definition;

import java.util.Objects;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

/**
 * 包装已有发现结果的展示定义，不安装 Handler；真实执行需使用独立的可执行定义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class DisplayCommandDefinition implements CommandDefinition {
    private final ResolvedCommandDTO descriptor;

    public DisplayCommandDefinition(ResolvedCommandDTO descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor);
    }

    @Override
    public ResolvedCommandDTO describe(CommandSessionSnapshotDTO session) {
        return descriptor;
    }
}
