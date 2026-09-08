/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.web;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.huawei.hicampus.claw.codingagent.runtimeapi.event.RuntimeEventSubscriber;
import com.huawei.hicampus.claw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把 Runtime 事件流写入 Spring MVC SseEmitter。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class RuntimeSseEmitterSubscriber implements RuntimeEventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(RuntimeSseEmitterSubscriber.class);

    private final SseEmitter emitter;

    private final boolean dataOnly;

    public RuntimeSseEmitterSubscriber(SseEmitter emitter) {
        this(emitter, false);
    }

    public RuntimeSseEmitterSubscriber(SseEmitter emitter, boolean dataOnly) {
        this.emitter = emitter;
        this.dataOnly = dataOnly;
    }

    @Override
    public void onEvent(RuntimeSseEventVO event) {
        if (dataOnly) {
            requireDataOnly(event);
            send(SseEmitter.event().data(event.getData()));
            return;
        }
        SseEmitter.SseEventBuilder builder =
                SseEmitter.event().name(event.getEvent()).data(event.getData());
        if (event.getId() != null) {
            builder.id(event.getId());
        }
        send(builder);
    }

    @Override
    public void onHeartbeat() {
        String comment = dataOnly ? " ping" : "heartbeat";
        send(SseEmitter.event().comment(comment));
    }

    @Override
    public void onComplete() {
        emitter.complete();
    }

    @Override
    public void onError(Throwable error) {
        log.debug("Runtime SSE client disconnected while events were being written", error);
        emitter.complete();
    }

    private void send(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void requireDataOnly(RuntimeSseEventVO event) {
        if (!event.isDataOnly()) {
            throw new IllegalArgumentException("v2 SSE output requires a data-only event");
        }
    }
}
