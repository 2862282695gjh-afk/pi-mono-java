/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.skill;

import java.util.regex.Pattern;

/**
 * Skill 领域共享常量与名称判定的统一定义，运行目录、加载、发现和客户端共同复用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class SkillConstants {
    public static final String DIRECTORY_NAME = "skills";

    public static final String MARKDOWN_FILE_NAME = "SKILL.md";

    public static final String MANIFEST_FILE_NAME = "skill.json";

    public static final String COMMAND_PREFIX = "skill:";

    public static final String ID_REGEX = "^skill-[0-9a-fA-F]{32}$";

    public static final Pattern ID_PATTERN = Pattern.compile(ID_REGEX);

    public static final int MAX_NAME_LENGTH = 64;

    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    public static final long MAX_FILE_BYTES = 1024L * 1024L;

    public static final String NAME_REGEX = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public static final Pattern NAME_PATTERN = Pattern.compile(NAME_REGEX);

    private SkillConstants() {}

    /**
     * 检查名称是否为 1 至 64 个小写字母、数字及分隔连字符，禁止首尾或连续连字符。
     *
     * @param name 候选 Skill 名称
     * @return 是否符合统一的 Skill 名称规则
     */
    public static boolean isValidName(String name) {
        return name != null
                && name.length() <= MAX_NAME_LENGTH
                && NAME_PATTERN.matcher(name).matches();
    }
}
