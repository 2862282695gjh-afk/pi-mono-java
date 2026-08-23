/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.cron.engine;

/**
 * 由宿主实现的 Cron Agent Session 执行边界。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface CronAgentSessionRunner {

    /**
     * 使用 Job 绑定的 Agent 执行提示词。
     *
     * @param agentId 受管 Agent 标识
     * @param prompt Job 提示词
     * @return 最终文本结果
     */
    String execute(String agentId, String prompt);
}
