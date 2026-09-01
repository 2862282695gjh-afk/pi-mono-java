/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.common.identifier;

import java.util.regex.Pattern;

/**
 * CampusClaw 类型化资源标识符的统一正则约束。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/21]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ResourceIdentifierPatterns {
    public static final String AGENT_ID_REGEX = "^agent-[0-9a-fA-F]{32}$";

    public static final Pattern AGENT_ID_PATTERN = Pattern.compile(AGENT_ID_REGEX);

    public static final String TOOL_ID_REGEX = "^tool-[0-9a-fA-F]{32}$";

    public static final Pattern TOOL_ID_PATTERN = Pattern.compile(TOOL_ID_REGEX);

    public static final String SKILL_ID_REGEX = "^skill-[0-9a-fA-F]{32}$";

    public static final Pattern SKILL_ID_PATTERN = Pattern.compile(SKILL_ID_REGEX);

    public static final String SESSION_ID_REGEX = "^session-[0-9a-fA-F]{32}$";

    public static final Pattern SESSION_ID_PATTERN = Pattern.compile(SESSION_ID_REGEX);

    private ResourceIdentifierPatterns() {}
}
