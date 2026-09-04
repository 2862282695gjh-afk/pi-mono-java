/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

/**
 * Versioned identity of the Skill selected at command resolution time. Listing,
 * admission and execution must use this snapshot instead of re-resolving by name;
 * command acceptance persists it as the invocation skill snapshot, so a later
 * Agent refresh can never silently execute a different version.
 *
 * @param agentId agent owning the skill at resolution time
 * @param agentVersion agent runtime version at resolution time
 * @param skillId Mate skill identifier
 * @param skillVersion skill version at resolution time
 * @param markdown SKILL.md content captured at resolution time
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record SkillCommandSnapshot(
        String agentId, String agentVersion, String skillId, String skillVersion, String markdown) {}
