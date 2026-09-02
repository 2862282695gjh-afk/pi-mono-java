/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.cron;

import com.huawei.hicampus.claw.agent.tool.AgentTool;
import com.huawei.hicampus.claw.cron.CronService;
import com.huawei.hicampus.claw.cron.tool.CronTool;

import org.springframework.stereotype.Component;

/**
 * 为 Runtime Session 创建绑定当前 Agent 的 Cron 工具实例。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class AgentScopedCronToolFactory {

    private final CronService cronService;

    public AgentScopedCronToolFactory(CronService cronService) {
        this.cronService = cronService;
    }

    public AgentTool create(String agentId) {
        return new CronTool(cronService, agentId);
    }
}
