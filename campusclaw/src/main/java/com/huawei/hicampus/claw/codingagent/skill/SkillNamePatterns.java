/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.skill;

import java.util.regex.Pattern;

/**
 * Skill 名称规则的单一来源，加载与命令发现使用相同的合法名称约束。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/03]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SkillNamePatterns {
    public static final int MAX_NAME_LENGTH = 64;

    public static final String NAME_REGEX = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public static final Pattern NAME_PATTERN = Pattern.compile(NAME_REGEX);

    private SkillNamePatterns() {}

    /**
     * 检查名称是否为 1 至 64 个小写字母、数字及分隔连字符，禁止首尾或连续连字符。
     *
     * @param name 候选 Skill 名称
     * @return 是否符合统一的 Skill 名称规则
     */
    public static boolean isValid(String name) {
        return name != null
                && name.length() <= MAX_NAME_LENGTH
                && NAME_PATTERN.matcher(name).matches();
    }
}
