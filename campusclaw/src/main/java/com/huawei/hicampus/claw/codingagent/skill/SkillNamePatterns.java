/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.skill;

import java.util.regex.Pattern;

/**
 * Single source of Skill name rules for the whole product. {@code LEGACY} keeps
 * loading compatibility for existing skills; {@code STRICT} follows the Agent
 * Skills specification and is used by command discovery and command execution
 * admission. Both share the same maximum name length.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SkillNamePatterns {
    /**
     * Loading-compatible rule: lowercase letters, digits and hyphens.
     */
    public static final Pattern LEGACY = Pattern.compile("^[a-z0-9-]+$");

    /**
     * Agent Skills rule: hyphens must not lead, trail or repeat.
     */
    public static final Pattern STRICT = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private SkillNamePatterns() {}

    /**
     * Checks whether the name satisfies the strict Agent Skills rule.
     *
     * @param name candidate Skill name
     * @return true when the name is valid for command discovery and execution
     */
    public static boolean isStrictValid(String name) {
        return isLengthValid(name) && STRICT.matcher(name).matches();
    }

    /**
     * Checks whether the name satisfies the legacy loading rule.
     *
     * @param name candidate Skill name
     * @return true when the name is valid for Skill loading
     */
    public static boolean isLegacyValid(String name) {
        return isLengthValid(name) && LEGACY.matcher(name).matches();
    }

    private static boolean isLengthValid(String name) {
        return name != null && !name.isEmpty() && name.length() <= Skill.MAX_NAME_LENGTH;
    }
}
