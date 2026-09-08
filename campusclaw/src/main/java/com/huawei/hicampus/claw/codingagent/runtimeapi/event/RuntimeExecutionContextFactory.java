/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.huawei.hicampus.claw.ai.types.Message;
import com.huawei.hicampus.claw.ai.types.Model;
import com.huawei.hicampus.claw.ai.types.UserMessage;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentDirectorySnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeExecutionContextDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.claw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

import org.springframework.stereotype.Component;

/**
 * 根据持久化 Session 快照准备模型、历史消息、Agent 和 SSE 执行上下文。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeExecutionContextFactory {
    private final RuntimeEventQueryService queryService;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final RuntimeEntryCodec codec;

    private final RuntimeEventStreamFactory streamFactory;

    private final Clock clock;

    public RuntimeExecutionContextFactory(
            RuntimeEventQueryService queryService,
            RuntimeSessionEngineRegistry engineRegistry,
            RuntimeEntryCodec codec,
            RuntimeEventStreamFactory streamFactory,
            Clock clock) {
        this.queryService = queryService;
        this.engineRegistry = engineRegistry;
        this.codec = codec;
        this.streamFactory = streamFactory;
        this.clock = clock;
    }

    public RuntimeExecutionContextDTO create(
            RuntimeSessionDTO session,
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            String message,
            List<String> fileIds,
            MateCredentials credentials) {
        List<Message> history = queryService.restoreHistory(session.getId(), model);
        UserMessage userMessage = codec.toUserMessage(message, fileIds, clock.millis());
        RuntimeEventStream stream = streamFactory.create();
        RuntimeActiveExecution execution = new RuntimeActiveExecution(stream);
        RuntimeSessionHolder holder = engineRegistry.register(
                session.getId(), snapshot, model, session.isThinking(), history, execution, credentials);
        return new RuntimeExecutionContextDTO(holder, execution, userMessage, message, stream);
    }

    public RuntimeExecutionContextDTO createPreparedMessage(
            RuntimeSessionDTO session,
            AgentDirectorySnapshotDTO snapshot,
            Model model,
            Function<PreparedAgentRuntime, String> messageFactory,
            List<String> fileIds,
            MateCredentials credentials) {
        List<Message> history = queryService.restoreHistory(session.getId(), model);
        RuntimeEventStream stream = streamFactory.create();
        RuntimeActiveExecution execution = new RuntimeActiveExecution(stream);

        // 准备回调在注册调用内同步执行；引用只属于本次请求，不存放到 Spring 单例。
        var message = new AtomicReference<String>();
        var userMessage = new AtomicReference<UserMessage>();
        RuntimeSessionHolder holder = engineRegistry.register(
                session.getId(), snapshot, model, session.isThinking(), history, execution, credentials, runtime -> {
                    String prepared = messageFactory.apply(runtime);
                    message.set(prepared);
                    userMessage.set(codec.toUserMessage(prepared, fileIds, clock.millis()));
                });
        return new RuntimeExecutionContextDTO(holder, execution, userMessage.get(), message.get(), stream);
    }
}
