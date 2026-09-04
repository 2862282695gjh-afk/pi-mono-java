/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.Objects;

/**
 * 包装已有发现结果的展示定义，不安装 Handler；Skill 执行由独立开发线扩展。
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
