/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 SLF4J 统一记录后台线程未捕获异常。
 *
 * <p>直接创建的后台线程必须在启动前注册未捕获异常处理器，否则线程可能只向
 * {@code System.err} 输出后静默终止。没有线程专属策略时应注册 {@link #INSTANCE}。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/13]
 * @since [br_eCampusCore 26.0.0]
 */
public final class LoggingUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    /** 供直接创建的后台线程共享的处理器实例。 */
    public static final LoggingUncaughtExceptionHandler INSTANCE = new LoggingUncaughtExceptionHandler();

    private static final Logger log = LoggerFactory.getLogger(LoggingUncaughtExceptionHandler.class);

    private static final String ERROR_CODE = "BACKGROUND_TASK_FAILED";

    private LoggingUncaughtExceptionHandler() {}

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", "background.thread")
                .addKeyValue("errorCode", ERROR_CODE)
                .addKeyValue("thread", t.getName())
                .setCause(e)
                .log(
                        "CampusClaw failure: operation={}, errorCode={}, context={{thread={}}}",
                        "background.thread",
                        ERROR_CODE,
                        t.getName());
    }
}
