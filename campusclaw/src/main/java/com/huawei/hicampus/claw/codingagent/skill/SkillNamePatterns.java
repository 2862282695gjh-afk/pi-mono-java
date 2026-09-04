/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.skill;

import java.util.regex.Pattern;

/**
 * Skill 名称规则的单一来源：加载保持旧格式兼容，命令发现使用严格规则。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SkillNamePatterns {
    public static final int MAX_NAME_LENGTH = 64;

    public static final String LEGACY_REGEX = "^[a-z0-9-]+$";

    public static final String STRICT_REGEX = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public static final Pattern LEGACY = Pattern.compile(LEGACY_REGEX);

    public static final Pattern STRICT = Pattern.compile(STRICT_REGEX);

    private SkillNamePatterns() {}

    /**
     * 检查名称是否符合严格 Agent Skills 规则。
     *
     * @param name 候选 Skill 名称
     * @return 是否符合命令发现规则
     */
    public static boolean isStrictValid(String name) {
        return isLengthValid(name) && STRICT.matcher(name).matches();
    }

    /**
     * 检查名称是否符合兼容加载规则。
     *
     * @param name 候选 Skill 名称
     * @return 是否符合加载规则
     */
    public static boolean isLegacyValid(String name) {
        return isLengthValid(name) && LEGACY.matcher(name).matches();
    }

    private static boolean isLengthValid(String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_NAME_LENGTH;
    }
}
