/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.web;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventSubscriber;
import com.campusclaw.codingagent.runtimeapi.vo.RuntimeSseEventVO;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把 Runtime 事件流写入 Spring MVC SseEmitter。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class RuntimeSseEmitterSubscriber implements RuntimeEventSubscriber {
    private final SseEmitter emitter;

    public RuntimeSseEmitterSubscriber(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onEvent(RuntimeSseEventVO event) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event.getEvent()).data(event.getData());
        if (event.getId() != null) {
            builder.id(event.getId());
        }
        send(builder);
    }

    @Override
    public void onHeartbeat() {
        send(SseEmitter.event().comment("heartbeat"));
    }

    @Override
    public void onComplete() {
        emitter.complete();
    }

    @Override
    public void onError(Throwable error) {
        emitter.complete();
    }

    private void send(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
