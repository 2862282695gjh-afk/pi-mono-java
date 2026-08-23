/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.builtin;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;

/**
 * 按关闭枚举为单个 Session 创建内置工具实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public interface BuiltInToolFactory {

    /**
     * 创建一个新的工具实例。
     *
     * @param name 内置工具名称
     * @param context Session 装配上下文
     * @return 新工具实例
     */
    AgentTool create(BuiltInToolName name, ToolAssemblyContext context);
}
