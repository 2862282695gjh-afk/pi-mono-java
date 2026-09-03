/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import java.util.regex.Pattern;

import com.huawei.hicampus.claw.codingagent.skill.Skill;

/**
 * Validator for Skill command names in the command discovery layer, aligned with
 * the Agent Skills specification: 1-{@value Skill#MAX_NAME_LENGTH} lowercase
 * letters, digits or hyphens; hyphens must not lead, trail or repeat. Skill
 * loading intentionally keeps its legacy tolerant rules for existing skills, so
 * this validator is specific to command discovery and execution.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SkillNameValidator {
    /**
     * Strict Skill name pattern shared by command discovery and execution.
     */
    public static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private SkillNameValidator() {}

    /**
     * Checks whether the name satisfies the strict Skill name rules.
     *
     * @param name candidate Skill name
     * @return true when the name is valid
     */
    public static boolean isValid(String name) {
        return name != null
                && !name.isEmpty()
                && name.length() <= Skill.MAX_NAME_LENGTH
                && SKILL_NAME_PATTERN.matcher(name).matches();
    }
}
