/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command.source;

import java.util.List;

import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.command.definition.CommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.definition.DisplayCommandDefinition;
import com.campusclaw.codingagent.runtimeapi.command.type.CommandKind;
import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

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
     * 从本次完整快照提供定义；依赖 Agent 数据的来源必须复用该快照，不再读取缓存或当前目录。
     *
     * @param session 已完成访问检查的 Session
     * @param prepared 已经 AgentRuntimeManager 完整性与目录身份校验的同源快照
     * @return 本次来源定义；不依赖 Agent 数据的来源可复用固定定义
     */
    default List<? extends CommandDefinition> definitions(RuntimeSessionDTO session, PreparedAgentRuntime prepared) {
        return definitions(session);
    }

    /**
     * 列出本来源为 Session 提供的命令。
     *
     * @param session 已完成访问检查的 Session
     * @return 按命令名称排序的发现结果
     */
    List<ResolvedCommandDTO> list(RuntimeSessionDTO session);
}
