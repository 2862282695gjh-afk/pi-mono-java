/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.event;

import java.time.Clock;

import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

import org.springframework.stereotype.Component;

/**
 * 使用统一持久化依赖创建单次执行的 Agent 事件投影器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeEventProjectorFactory {
    private final RuntimeSessionRepository repository;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator idGenerator;

    private final Clock clock;

    public RuntimeEventProjectorFactory(
            RuntimeSessionRepository repository,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator idGenerator,
            Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public RuntimeEventProjector create(
            RuntimeSessionHolder holder, RuntimeActiveExecution execution, UserMessage initialUserMessage) {
        return new RuntimeEventProjector(
                holder.sessionId(),
                repository,
                codec,
                idGenerator,
                execution.eventStream(),
                clock,
                holder.agent()::abort,
                execution,
                initialUserMessage);
    }
}
