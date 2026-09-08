/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.common.constant;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CampusClaw 共享业务常量的唯一定义入口，按领域分组供各模块复用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ClawConstants {
    private ClawConstants() {}

    /**
     * 用户级配置目录与覆盖入口的固定名称。
     */
    public static final class Home {
        public static final String PROPERTY = "campusclaw.home";

        public static final String ENVIRONMENT_VARIABLE = "CAMPUSCLAW_HOME";

        public static final String CONFIG_DIRECTORY_NAME = ".campusclaw";

        public static final String AGENT_DIRECTORY_NAME = "agent";

        private Home() {}
    }

    /**
     * Agent 资源标识约束。
     */
    public static final class Agent {
        public static final String ID_REGEX = "^agent-[0-9a-fA-F]{32}$";

        public static final Pattern ID_PATTERN = Pattern.compile(ID_REGEX);

        private Agent() {}
    }

    /**
     * Tool 资源标识约束。
     */
    public static final class Tool {
        public static final String ID_REGEX = "^tool-[0-9a-fA-F]{32}$";

        public static final Pattern ID_PATTERN = Pattern.compile(ID_REGEX);

        private Tool() {}
    }

    /**
     * Session 资源标识约束。
     */
    public static final class Session {
        public static final String ID_REGEX = "^session-[0-9a-fA-F]{32}$";

        public static final Pattern ID_PATTERN = Pattern.compile(ID_REGEX);

        public static final int MAX_DISPLAY_NAME_BYTES = 80;

        public static final String FORBIDDEN_DISPLAY_NAME_REGEX = "[\\p{Cc}\\p{Cs}\\u202A-\\u202E\\u2066-\\u2069]";

        public static final Pattern FORBIDDEN_DISPLAY_NAME_PATTERN = Pattern.compile(FORBIDDEN_DISPLAY_NAME_REGEX);

        private Session() {}
    }

    /**
     * 受管 Agent 运行目录的共享文件约定。
     */
    public static final class Runtime {
        public static final String DIRECTORY_NAME = ".campusclaw";

        public static final String AGENT_FILE_NAME = "agent.json";

        public static final String SETTINGS_FILE_NAME = "settings.json";

        public static final String SYSTEM_FILE_NAME = "SYSTEM.md";

        private Runtime() {}
    }

    /**
     * Runtime HTTP 路径、模型格式和请求上限。
     */
    public static final class RuntimeApi {
        public static final String BASE_PATH = "/campusclaw-service/v1";

        public static final String MODEL_ID_REGEX = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$";

        public static final Pattern MODEL_ID_PATTERN = Pattern.compile(MODEL_ID_REGEX);

        public static final int MAX_MESSAGE_CHARACTERS = 262144;

        public static final int MAX_FILE_IDS = 32;

        /**
         * 命令发现响应的固定展示顺序与输入提示，不承担执行分派。
         */
        public static final class Command {
            public static final int CATALOG_RETRY_AFTER_SECONDS = 3;

            public static final List<String> BUILTIN_ORDER =
                    List.of("help", "status", "name", "model", "thinking", "compact", "skills");

            public static final Map<String, String> BUILTIN_INPUT_HINTS =
                    Map.of("name", "[displayName]", "model", "[modelId]", "thinking", "[on|off]");

            public static final String SKILL_INPUT_HINT = "[request]";

            private Command() {}
        }

        private RuntimeApi() {}
    }

    /**
     * Mate 调用的共享凭据请求头、权限值和响应错误码。
     */
    public static final class Mate {
        public static final String X_HW_ID = "X-HW-ID";

        public static final String X_HW_APPKEY = "X-HW-APPKEY";

        public static final String AUTHORIZATION = "Authorization";

        public static final String ACCESS_TOKEN = "access-token";

        public static final String TOOL_PERMISSION_ALLOW = "allow";

        public static final String TOOL_RESPONSE_INVALID = "MATE_TOOL_RESPONSE_INVALID";

        private Mate() {}
    }

    /**
     * Skill 标识、名称判定、长度限制和文件约定。
     */
    public static final class Skill {
        public static final String DIRECTORY_NAME = "skills";

        public static final String MARKDOWN_FILE_NAME = "SKILL.md";

        public static final String MANIFEST_FILE_NAME = "skill.json";

        public static final String COMMAND_PREFIX = "skill:";

        public static final String ID_REGEX = "^skill-[0-9a-fA-F]{32}$";

        public static final Pattern ID_PATTERN = Pattern.compile(ID_REGEX);

        public static final int MAX_NAME_LENGTH = 64;

        public static final int MAX_COMMAND_NAME_LENGTH = MAX_NAME_LENGTH + 6;

        public static final int MAX_DESCRIPTION_LENGTH = 1024;

        public static final long MAX_FILE_BYTES = 1024L * 1024L;

        private static final String NAME_EXPRESSION = "[a-z0-9]+(?:-[a-z0-9]+)*";

        public static final String NAME_REGEX = "^" + NAME_EXPRESSION + "$";

        public static final Pattern NAME_PATTERN = Pattern.compile(NAME_REGEX);

        public static final String COMMAND_NAME_REGEX = "^" + COMMAND_PREFIX + NAME_EXPRESSION + "$";

        public static final Pattern COMMAND_NAME_PATTERN = Pattern.compile(COMMAND_NAME_REGEX);

        private Skill() {}

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
}
