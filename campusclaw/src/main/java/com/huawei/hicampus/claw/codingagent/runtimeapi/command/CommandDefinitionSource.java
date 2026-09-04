/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * 按 Session 提供展示或可执行定义；注册表负责单次解析和精确查找。
 * 不触发 Agent 刷新或模型调用；来源过滤必须发生在解析之前。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public interface CommandDefinitionSource {
    CommandKind kind();

    default List<? extends CommandDefinition> definitions(RuntimeSessionDTO session) {
        return list(session).stream().map(DisplayCommandDefinition::new).toList();
    }

    /**
     * 列出本来源为 Session 提供的命令。
     *
     * @param session 已完成访问检查的 Session
     * @return 按命令名称排序的发现结果
     */
    List<ResolvedCommandDTO> list(RuntimeSessionDTO session);
}
