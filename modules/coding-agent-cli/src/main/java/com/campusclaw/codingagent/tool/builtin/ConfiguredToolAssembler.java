/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.builtin;

import java.util.List;

import com.campusclaw.agent.tool.AgentTool;

/**
 * 根据启动期严格配置装配一个 Session 的工具集合。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public interface ConfiguredToolAssembler {

    /**
     * 按入口 profile 顺序创建工具。
     *
     * @param entryPoint Session 创建入口
     * @param context Session 工具上下文
     * @return 不可变的新工具实例列表
     */
    List<AgentTool> assemble(ToolEntryPoint entryPoint, ToolAssemblyContext context);
}
