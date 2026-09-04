/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

/**
 * 命令发现时取得的 Skill 身份与正文快照；不承担独立开发线的执行或调用快照持久化。
 *
 * @param agentId 所属 Agent 标识
 * @param agentVersion Agent 版本
 * @param skillId Skill 标识
 * @param skillVersion Skill 版本
 * @param markdown 发现时的 SKILL.md 正文
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record SkillCommandSnapshotDTO(
        String agentId, String agentVersion, String skillId, String skillVersion, String markdown) {}
