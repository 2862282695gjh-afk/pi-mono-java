/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import org.springframework.stereotype.Component;

/**
 * 依据统一缓冲和心跳配置创建一次性的 SSE 事件流。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeEventStreamFactory {
    private final RuntimeEventProperties properties;

    private final RuntimeEntryCodec codec;

    public RuntimeEventStreamFactory(RuntimeEventProperties properties, RuntimeEntryCodec codec) {
        this.properties = properties;
        this.codec = codec;
    }

    public RuntimeEventStream create() {
        return new RuntimeEventStream(
                properties.getStreamBufferEvents(),
                properties.getStreamBufferBytes(),
                properties.getHeartbeatInterval(),
                codec::encodedSseBytes);
    }
}
