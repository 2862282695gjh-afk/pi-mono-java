/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.loop;

import java.util.Objects;

import com.huawei.hicampus.mate.matecampusclaw.agent.context.ContextTransformer;
import com.huawei.hicampus.mate.matecampusclaw.agent.context.DefaultMessageConverter;
import com.huawei.hicampus.mate.matecampusclaw.agent.context.MessageConverter;
import com.huawei.hicampus.mate.matecampusclaw.agent.queue.MessageQueue;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.mate.matecampusclaw.ai.CampusClawAiService;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Model;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.SimpleStreamOptions;

/**
 * 保存运行 Agent 循环所需的配置。
 *
 * <p>同时支持既有 {@link CampusClawAiService} 和可插拔 {@link StreamFunction}。
 * 显式提供 {@code streamFunction} 时优先使用该函数。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record AgentLoopConfig(
        CampusClawAiService piAiService,
        Model model,
        MessageConverter convertToLlm,
        ContextTransformer transformContext,
        ToolExecutionPipeline toolPipeline,
        MessageQueue steeringQueue,
        MessageQueue followUpQueue,
        SimpleStreamOptions streamOptions,
        StreamFunction streamFunction,
        SteeringMessageSupplier getSteeringMessages,
        SteeringMessageSupplier getFollowUpMessages) {

    /**
     * 保留未引入可插拔 {@code StreamFunction} 时的构造方式。
     * 该构造方法把三个扩展参数以 {@code null} 传给主构造方法。
     *
     * @param piAiService 驱动 LLM 流式调用的服务
     * @param model 目标 LLM 模型
     * @param convertToLlm 内部消息到 LLM 消息的转换器
     * @param transformContext 每轮调用前执行的异步上下文转换器
     * @param toolPipeline 执行 LLM 工具调用的 Pipeline
     * @param steeringQueue 当前执行中的 steer 消息队列
     * @param followUpQueue 每轮结束后的 follow-up 消息队列
     * @param streamOptions 温度和 token 上限等基础流式选项
     */
    public AgentLoopConfig(
            CampusClawAiService piAiService,
            Model model,
            MessageConverter convertToLlm,
            ContextTransformer transformContext,
            ToolExecutionPipeline toolPipeline,
            MessageQueue steeringQueue,
            MessageQueue followUpQueue,
            SimpleStreamOptions streamOptions) {
        this(
                piAiService,
                model,
                convertToLlm,
                transformContext,
                toolPipeline,
                steeringQueue,
                followUpQueue,
                streamOptions,
                null,
                null,
                null);
    }

    public AgentLoopConfig {
        Objects.requireNonNull(model, "model");
        if (piAiService == null && streamFunction == null) {
            throw new IllegalArgumentException("Either piAiService or streamFunction must be provided");
        }
        convertToLlm = convertToLlm != null ? convertToLlm : new DefaultMessageConverter();
        toolPipeline = toolPipeline != null ? toolPipeline : new ToolExecutionPipeline();
        steeringQueue = steeringQueue != null ? steeringQueue : new MessageQueue();
        followUpQueue = followUpQueue != null ? followUpQueue : new MessageQueue();
        streamOptions = streamOptions != null ? streamOptions : SimpleStreamOptions.empty();
    }

    /**
     * 返回实际使用的流式调用函数；未显式配置时包装 {@link CampusClawAiService}。
     *
     * @return 实际使用的流式调用函数
     */
    public StreamFunction effectiveStreamFunction() {
        if (streamFunction != null) {
            return streamFunction;
        }
        return piAiService::streamSimple;
    }
}
