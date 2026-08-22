/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;

/**
 * 用于初始化单个托管 Agent Session 的不可变本地快照。
 *
 * @param agentId 已选择的 Agent 标识
 * @param agentRoot 本地 {@code ./agent/{agentId}} 目录
 * @param metadata 缓存的运行时元数据
 * @param skills 从已物化 SKILL.md frontmatter 重建的绑定 Skill 身份快照；
 *               {@code skills/} 子目录本身表示绑定关系
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record PreparedAgentRuntime(String agentId, Path agentRoot, AgentRuntime metadata, List<SkillInfo> skills) {

    public PreparedAgentRuntime {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /**
     * 返回显式配置 {@code permission=allow} 的 Agent 级工具。
     * 在主 Agent 接入交互审批解析器之前，ASK 权限按 fail closed 处理。
     *
     * @return 允许的本地工具名
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
     * 按运行时名称查找已绑定的 Skill。
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
