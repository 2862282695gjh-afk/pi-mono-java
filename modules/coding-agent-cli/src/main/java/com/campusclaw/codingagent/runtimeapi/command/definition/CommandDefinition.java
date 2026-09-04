/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command.definition;

import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

/**
 * 命令定义只负责生成展示信息；是否可执行由具体定义类型表达。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public interface CommandDefinition {
    ResolvedCommandDTO describe(CommandSessionSnapshotDTO session);
}
