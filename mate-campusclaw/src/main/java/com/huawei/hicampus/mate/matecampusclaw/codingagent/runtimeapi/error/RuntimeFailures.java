/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * 在 Runtime 失败源头记录诊断信息，并创建仅携带错误码的异常。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/26]
 * @since [br_eCampusCore 26.0.0]
 */
public final class RuntimeFailures {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeFailures.class);

    private static final String FAILURE_EVENT = "campusclaw.failure";

    private RuntimeFailures() {}

    public static RuntimeApiException raise(String operation, RuntimeErrorCode errorCode, Object... context) {
        record(operation, errorCode, null, context);
        return new RuntimeApiException(errorCode);
    }

    public static RuntimeApiException raise(
            String operation, RuntimeErrorCode errorCode, Throwable error, Object... context) {
        record(operation, errorCode, error, context);
        return new RuntimeApiException(errorCode);
    }

    public static void record(String operation, RuntimeErrorCode errorCode, Object... context) {
        record(operation, errorCode, null, context);
    }

    public static void record(String operation, RuntimeErrorCode errorCode, Throwable error, Object... contextValues) {
        Map<String, Object> context = context(contextValues);
        LoggingEventBuilder event = errorCode.status().is4xxClientError() ? LOGGER.atWarn() : LOGGER.atError();
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
