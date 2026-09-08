/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * GET 历史与 POST 完整帧共享的只读事件响应。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
@RequiredArgsConstructor
public class SessionEventResponseVO {
    private final String eventId;

    private final String type;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String createdAt;

    @JsonUnwrapped
    private final EventDetailsResponseVO details;

    /**
     * 公共完整事件的类型化业务字段。
     */
    public sealed interface EventDetailsResponseVO
            permits UserMessageResponseVO,
                    UserInterruptResponseVO,
                    UserToolConfirmationResponseVO,
                    AgentMessageResponseVO,
                    AgentThinkingResponseVO,
                    AgentToolCallResponseVO,
                    AgentToolResultResponseVO,
                    SessionStatusIdleResponseVO,
                    SessionModelChangedResponseVO,
                    SessionThinkingChangedResponseVO,
                    SessionCompactedResponseVO {}

    /**
     * 用户输入内容块。
     */
    public sealed interface UserContentResponseVO permits TextContentResponseVO, FileContentResponseVO {}

    /**
     * 文本内容块响应。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class TextContentResponseVO implements UserContentResponseVO {
        private final String type;

        private final String text;
    }

    /**
     * 文件内容块响应。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class FileContentResponseVO implements UserContentResponseVO {
        private final String type;

        private final String fileId;
    }

    /**
     * 用户消息事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class UserMessageResponseVO implements EventDetailsResponseVO {
        private final List<UserContentResponseVO> content;
    }

    /**
     * 用户中断事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class UserInterruptResponseVO implements EventDetailsResponseVO {
        private final String targetEventId;
    }

    /**
     * 用户工具确认事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class UserToolConfirmationResponseVO implements EventDetailsResponseVO {
        private final String toolCallId;

        private final String result;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String denyMessage;
    }

    /**
     * Agent 正文事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class AgentMessageResponseVO implements EventDetailsResponseVO {
        private final String phase;

        private final String content;

        private final String sourceEventId;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final UsageResponseVO usage;
    }

    /**
     * Agent 思考摘要事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class AgentThinkingResponseVO implements EventDetailsResponseVO {
        private final String phase;

        private final String content;

        private final String sourceEventId;
    }

    /**
     * Agent 工具调用事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class AgentToolCallResponseVO implements EventDetailsResponseVO {
        private final String toolCallId;

        private final String toolName;

        private final Map<String, Object> arguments;

        private final boolean requiresConfirmation;

        private final String sourceEventId;
    }

    /**
     * Agent 工具结果事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class AgentToolResultResponseVO implements EventDetailsResponseVO {
        private final String toolCallId;

        private final List<TextContentResponseVO> content;

        @Getter(AccessLevel.NONE)
        private final boolean isError;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String errorCode;

        private final String sourceEventId;

        /**
         * 返回工具调用是否失败，并保持契约要求的 isError 字段名。
         *
         * @return 是否失败
         */
        @JsonProperty("isError")
        public boolean isError() {
            return isError;
        }
    }

    /**
     * Session 空闲状态事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class SessionStatusIdleResponseVO implements EventDetailsResponseVO {
        private final String reason;

        private final String sourceEventId;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String errorCode;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String message;
    }

    /**
     * Session 模型变更事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class SessionModelChangedResponseVO implements EventDetailsResponseVO {
        private final String previousModelId;

        private final String modelId;

        private final String reason;
    }

    /**
     * Session 思考配置变更事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class SessionThinkingChangedResponseVO implements EventDetailsResponseVO {
        private final boolean previousThinking;

        private final boolean thinking;

        private final String reason;
    }

    /**
     * Session 压缩完成事件字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class SessionCompactedResponseVO implements EventDetailsResponseVO {
        private final String reason;

        private final long tokensBefore;

        private final long estimatedTokensAfter;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final String sourceEventId;
    }

    /**
     * 单次模型响应用量字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class UsageResponseVO {
        private final long input;

        private final long output;

        private final long cacheRead;

        private final long cacheWrite;

        private final long totalTokens;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final CostResponseVO cost;
    }

    /**
     * 单次模型响应费用字段。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class CostResponseVO {
        private final BigDecimal input;

        private final BigDecimal output;

        private final BigDecimal cacheRead;

        private final BigDecimal cacheWrite;

        private final BigDecimal total;
    }
}
