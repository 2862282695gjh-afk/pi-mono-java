/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.List;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * 按 Session 发现命令，只提供列表；注册表在单次结果上负责精确查找。
 * 不触发 Agent 刷新或模型调用；可执行定义与准入策略由后续 Builtin 层提供。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public interface CommandDefinitionSource {
    /**
     * 列出本来源为 Session 提供的命令。
     *
     * @param session 已完成访问检查的 Session
     * @return 按命令名称排序的发现结果
     */
    List<ResolvedCommandDTO> list(RuntimeSessionDTO session);
}
