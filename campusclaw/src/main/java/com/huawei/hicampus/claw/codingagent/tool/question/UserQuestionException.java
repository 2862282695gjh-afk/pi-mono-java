/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.question;

import java.util.Map;

import com.huawei.hicampus.claw.agent.error.StableErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户问题无法安全等待或续跑时返回稳定工具错误码。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public class UserQuestionException extends RuntimeException implements StableErrorCode {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserQuestionException.class);

    private final String errorCode;

    public UserQuestionException(String errorCode) {
        this(errorCode, null);
    }

    public UserQuestionException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    /**
     * 记录可定位的失败原因并保留异常链；上下文只允许标识、状态和计数。
     *
     * @param errorCode 稳定错误码
     * @param operation 失败操作
     * @param context 不含业务载荷和凭据的诊断字段
     * @param cause 原始异常，可为空
     * @return 交给工具管线处理的领域异常
     */
    public static UserQuestionException failure(
            String errorCode, String operation, Map<String, ?> context, Throwable cause) {
        UserQuestionException failure = new UserQuestionException(errorCode, cause);
        var event = LOGGER.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", operation)
                .addKeyValue("errorCode", errorCode)
                .setCause(failure);
        context.forEach(event::addKeyValue);
        event.log("User question failed: errorCode={}, operation={}, context={}", errorCode, operation, context);
        return failure;
    }

    @Override
    public String stableErrorCode() {
        return errorCode;
    }
}
