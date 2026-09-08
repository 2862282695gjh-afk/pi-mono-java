/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.util.Locale;

import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

import org.springframework.stereotype.Component;

/**
 * 使用共享持久化与公共投影依赖创建单个 v2 执行段投影器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeV2EventProjectorFactory {
    private final RuntimeSessionRepository repository;

    private final RuntimeExecutionPersistenceService persistence;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeCommittedEventFactory events;

    private final RuntimeV2EventEncoder encoder;

    private final Clock clock;

    private final RuntimeEventProperties properties;

    public RuntimeV2EventProjectorFactory(
            RuntimeSessionRepository repository,
            RuntimeExecutionPersistenceService persistence,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeCommittedEventFactory events,
            RuntimeV2EventEncoder encoder,
            Clock clock,
            RuntimeEventProperties properties) {
        this.repository = repository;
        this.persistence = persistence;
        this.codec = codec;
        this.ids = ids;
        this.events = events;
        this.encoder = encoder;
        this.clock = clock;
        this.properties = properties;
    }

    public RuntimeV2EventProjector create(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            UserMessage initialUserMessage,
            Locale locale) {
        return new RuntimeV2EventProjector(
                holder.sessionId(),
                execution.target().rootEventId(),
                repository,
                persistence,
                codec,
                ids,
                events,
                encoder,
                clock,
                properties.getStreamBufferBytes(),
                holder::abort,
                execution,
                initialUserMessage,
                holder.thinking(),
                locale);
    }
}
