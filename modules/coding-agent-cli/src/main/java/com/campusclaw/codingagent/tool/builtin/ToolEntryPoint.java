/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.builtin;

/**
 * 定义受管 AgentSession 的三个创建入口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public enum ToolEntryPoint {
    RUNTIME,
    CRON,
    CHILD_AGENT
}
