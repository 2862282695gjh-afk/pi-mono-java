/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * 收集 AI 模块测试日志事件的 Log4j2 Appender。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/29]
 * @since [br_eCampusCore 26.0.0]
 */
public final class Log4j2TestAppender extends AbstractAppender {
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    public Log4j2TestAppender(String name) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }

    public List<LogEvent> events() {
        return List.copyOf(events);
    }
}
