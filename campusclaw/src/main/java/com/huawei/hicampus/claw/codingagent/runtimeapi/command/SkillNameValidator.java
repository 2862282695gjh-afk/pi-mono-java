/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import java.util.regex.Pattern;

/**
 * Validator for Skill command names aligned with the Agent Skills specification:
 * 1-64 lowercase letters, digits or hyphens; hyphens must not lead, trail or repeat.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SkillNameValidator {
    /**
     * Strict Skill name pattern shared by command routing and Skill loading.
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
                && name.length() <= 64
                && SKILL_NAME_PATTERN.matcher(name).matches();
    }
}
