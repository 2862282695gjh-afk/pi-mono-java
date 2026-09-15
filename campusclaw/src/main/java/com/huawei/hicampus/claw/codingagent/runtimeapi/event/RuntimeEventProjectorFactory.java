/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.util.Locale;

import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeExecutionPersistenceService;
import com.huawei.hicampus.claw.codingagent.runtimeapi.persistence.RuntimeSessionRepository;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;
import com.huawei.hicampus.claw.codingagent.tool.question.QuestionRequestParser;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 使用共享持久化与公共投影依赖创建单个执行段投影器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeEventProjectorFactory {
    private final RuntimeSessionRepository repository;

    private final RuntimeExecutionPersistenceService persistence;

    private final RuntimeEntryCodec codec;

    private final RuntimeEntryIdGenerator ids;

    private final RuntimeCommittedEventFactory events;

    private final RuntimeEventEncoder encoder;

    private final Clock clock;

    private final RuntimeEventProperties properties;

    private final RuntimeTodoEventMapper todoMapper;

    private final QuestionRequestParser questionParser;

    public RuntimeEventProjectorFactory(
            RuntimeSessionRepository repository,
            RuntimeExecutionPersistenceService persistence,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeCommittedEventFactory events,
            RuntimeEventEncoder encoder,
            Clock clock,
            RuntimeEventProperties properties,
            RuntimeTodoEventMapper todoMapper) {
        this(
                repository,
                persistence,
                codec,
                ids,
                events,
                encoder,
                clock,
                properties,
                todoMapper,
                new QuestionRequestParser(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Autowired
    public RuntimeEventProjectorFactory(
            RuntimeSessionRepository repository,
            RuntimeExecutionPersistenceService persistence,
            RuntimeEntryCodec codec,
            RuntimeEntryIdGenerator ids,
            RuntimeCommittedEventFactory events,
            RuntimeEventEncoder encoder,
            Clock clock,
            RuntimeEventProperties properties,
            RuntimeTodoEventMapper todoMapper,
            QuestionRequestParser questionParser) {
        this.repository = repository;
        this.persistence = persistence;
        this.codec = codec;
        this.ids = ids;
        this.events = events;
        this.encoder = encoder;
        this.clock = clock;
        this.properties = properties;
        this.todoMapper = todoMapper;
        this.questionParser = questionParser;
    }

    public RuntimeEventProjector create(
            RuntimeSessionHolder holder,
            RuntimeActiveExecution execution,
            UserMessage initialUserMessage,
            Locale locale) {
        return new RuntimeEventProjector(
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
                locale,
                todoMapper,
                questionParser);
    }
}
