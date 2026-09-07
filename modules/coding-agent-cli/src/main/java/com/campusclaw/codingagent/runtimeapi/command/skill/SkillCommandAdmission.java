/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command.skill;

import java.util.Objects;
import java.util.function.Consumer;

import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.campusclaw.common.constant.ClawConstants;

/**
 * 在本次实际执行的 Agent 快照上核对显式 Skill 名称及直接绑定。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SkillCommandAdmission implements Consumer<PreparedAgentRuntime> {
    private final String agentId;

    private final String skillName;

    public SkillCommandAdmission(String agentId, String skillName) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        if (!ClawConstants.Skill.isValidName(skillName)) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        this.skillName = skillName;
    }

    @Override
    public void accept(PreparedAgentRuntime runtime) {
        requireBoundSkill(runtime);
    }

    /**
     * 返回实际快照中的直接绑定，不查询旧清单，也不重新读取 Skill 文件。
     *
     * @param runtime 公共 Session 工厂本次准备的完整 Agent 快照
     * @return 名称精确匹配的绑定 Skill
     * @throws RuntimeApiException Agent 不可用或未直接绑定指定 Skill 时抛出
     */
    public SkillInfo requireBoundSkill(PreparedAgentRuntime runtime) {
        if (runtime == null
                || !agentId.equals(runtime.agentId())
                || runtime.metadata() == null
                || !agentId.equals(runtime.metadata().id())
                || !Boolean.TRUE.equals(runtime.metadata().enabled())) {
            throw new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
        }
        return runtime.findSkill(skillName)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.COMMAND_NOT_FOUND));
    }
}
