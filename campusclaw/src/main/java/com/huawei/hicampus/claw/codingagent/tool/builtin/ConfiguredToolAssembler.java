/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.builtin;

import java.util.List;

import com.huawei.hicampus.claw.agent.tool.AgentTool;

/**
 * 根据入口配置和可信 Agent 工具关联装配一个 Session 的工具集合。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public interface ConfiguredToolAssembler {

    /**
     * 按入口配置顺序创建框架工具，再追加 Agent 直接工具代理；空配置返回空集合。
     *
     * @param entryPoint Session 创建入口
     * @param context Session 工具上下文
     * @return 不可变的新工具实例列表
     */
    List<AgentTool> assemble(ToolEntryPoint entryPoint, ToolAssemblyContext context);
}
