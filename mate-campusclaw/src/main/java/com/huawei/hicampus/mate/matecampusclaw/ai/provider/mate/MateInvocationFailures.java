/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.provider.mate;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * 在 Mate 调用失败源头记录诊断信息，并创建仅携带错误码的异常。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
final class MateInvocationFailures {
    private static final Logger LOGGER = LoggerFactory.getLogger(MateInvocationFailures.class);

    private static final String FAILURE_EVENT = "campusclaw.failure";

    private MateInvocationFailures() {}

    static MateModelInvocationException raise(String operation, MateInvocationErrorCode errorCode, Object... context) {
        record(operation, errorCode, null, context);
        return new MateModelInvocationException(errorCode);
    }

    static MateModelInvocationException raise(
            String operation, MateInvocationErrorCode errorCode, Throwable error, Object... context) {
        record(operation, errorCode, error, context);
        return new MateModelInvocationException(errorCode);
    }

    static void record(String operation, MateInvocationErrorCode errorCode, Object... context) {
        record(operation, errorCode, null, context);
    }

    static void record(String operation, MateInvocationErrorCode errorCode, Throwable error, Object... contextValues) {
        Map<String, Object> context = context(contextValues);
        LoggingEventBuilder event = errorCode.warning() ? LOGGER.atWarn() : LOGGER.atError();
        event.addKeyValue("event", FAILURE_EVENT)
                .addKeyValue("operation", operation)
                .addKeyValue("errorCode", errorCode.name());
        context.forEach(event::addKeyValue);
        if (error != null) {
            event.setCause(error);
        }
        event.log("CampusClaw failure: operation={}, errorCode={}, context={}", operation, errorCode.name(), context);
    }

    private static Map<String, Object> context(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("failure log context must contain key/value pairs");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            context.put(String.valueOf(values[index]), values[index + 1]);
        }
        return context;
    }
}
