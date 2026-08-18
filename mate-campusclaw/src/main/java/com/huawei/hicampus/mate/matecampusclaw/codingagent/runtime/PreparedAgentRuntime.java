/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillInfo;

/**
 * Immutable local snapshot used to initialize one managed Agent session.
 *
 * @param agentId   selected Agent identifier
 * @param agentRoot local {@code ./agent/{agentId}} directory
 * @param metadata  cached runtime metadata
 * @param skills    identity snapshots (name/description) of the bound Skills, reconstructed
 *                  from the materialized SKILL.md front-matter headers; the materialized
 *                  {@code skills/} sub-directories themselves prove the binding
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record PreparedAgentRuntime(String agentId, Path agentRoot, AgentRuntime metadata, List<SkillInfo> skills) {

    public PreparedAgentRuntime {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /**
     * Returns Agent-level tools explicitly permitted with {@code permission=allow}.
     * ASK is fail-closed until the main Agent has an interactive approval resolver.
     *
     * @return allowed local tool names
     */
    public List<String> allowedAgentToolNames() {
        return allowedToolNames(metadata.bindingTools());
    }

    /**
     * 返回本地快照中记录的直接子 Agent 绑定。
     * 绑定仅是候选元数据；鉴权、祖先链与深度过滤在委派时刻进行，绝不在本方法内过滤。
     *
     * @return 不可变绑定列表；Agent 未委派任何子 Agent 时为空列表
     */
    public List<AgentReference> bindingAgents() {
        return metadata.bindingAgents();
    }

    /**
     * 按运行期名称查找已绑定的 Skill。
     *
     * @param skillName Skill 名称
     * @return 已绑定的 Skill 元数据
     */
    public Optional<SkillInfo> findSkill(String skillName) {
        return skills.stream().filter(skill -> skillName.equals(skill.name())).findFirst();
    }

    static List<String> allowedToolNames(List<BoundTool> tools) {
        return tools.stream()
                .filter(tool -> tool.name() != null && !tool.name().isBlank())
                .filter(tool -> "allow".equals(normalizePermission(tool.permission())))
                .map(BoundTool::name)
                .distinct()
                .toList();
    }

    private static String normalizePermission(String permission) {
        return permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
    }
}
