/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.agent;

import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO.PromptSource;

/**
 * 为不关注模式提示词的测试提供最小有效快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
public final class AgentPromptTestFixtures {
    private AgentPromptTestFixtures() {}

    public static AgentPromptSnapshotDTO prompts(String basePrompt) {
        return new AgentPromptSnapshotDTO(basePrompt, "Plan", "Build", PromptSource.BUILTIN, PromptSource.BUILTIN);
    }
}
