/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.event;

import java.time.Clock;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Message;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.UserMessage;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent.AgentDirectoryResolver;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.model.RuntimeModelManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionEngineRegistry;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

import org.springframework.stereotype.Component;

/**
 * 根据持久化 Session 快照准备模型、历史消息、Agent 和 SSE 执行上下文。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/19]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class RuntimeExecutionContextFactory {
    private final AgentDirectoryResolver agentDirectoryResolver;

    private final RuntimeModelManager modelManager;

    private final RuntimeEventQueryService queryService;

    private final RuntimeSessionEngineRegistry engineRegistry;

    private final RuntimeEntryCodec codec;

    private final RuntimeEventStreamFactory streamFactory;

    private final Clock clock;

    public RuntimeExecutionContextFactory(
            AgentDirectoryResolver agentDirectoryResolver,
            RuntimeModelManager modelManager,
            RuntimeEventQueryService queryService,
            RuntimeSessionEngineRegistry engineRegistry,
            RuntimeEntryCodec codec,
            RuntimeEventStreamFactory streamFactory,
            Clock clock) {
        this.agentDirectoryResolver = agentDirectoryResolver;
        this.modelManager = modelManager;
        this.queryService = queryService;
        this.engineRegistry = engineRegistry;
        this.codec = codec;
        this.streamFactory = streamFactory;
        this.clock = clock;
    }

    public RuntimeExecutionContext create(RuntimeSessionDTO session, String message, List<String> fileIds) {
        var snapshot = agentDirectoryResolver.resolve(session.getAgentId());
        Model model = modelManager.resolveModel(snapshot, session.getModelId());
        List<Message> history = queryService.restoreHistory(session.getId(), model);
        UserMessage userMessage = codec.toUserMessage(message, fileIds, clock.millis());
        RuntimeActiveExecution execution = new RuntimeActiveExecution(streamFactory.create());
        RuntimeSessionHolder holder =
                engineRegistry.register(session.getId(), snapshot, model, session.isThinking(), history, execution);
        return new RuntimeExecutionContext(holder, execution, userMessage);
    }
}
