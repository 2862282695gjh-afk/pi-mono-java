/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai;

import java.util.List;
import java.util.Objects;

import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.provider.AiProviderRegistry;
import com.campusclaw.ai.provider.ApiProvider;
import com.campusclaw.ai.provider.ApiProviderRegistry;
import com.campusclaw.ai.provider.ProviderId;
import com.campusclaw.ai.stream.AssistantMessageEventStream;
import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.Context;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.SimpleStreamOptions;
import com.campusclaw.ai.types.StreamOptions;
import com.campusclaw.ai.types.UserMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.Nullable;
import reactor.core.publisher.Mono;

/**
 * 提供统一的大模型流式与完整响应调用入口。
 *
 * <p>优先按模型的 Provider 身份查找通用 Provider；未命中时兼容按 API 协议路由的旧 Provider。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CampusClawAiService {

    private final ApiProviderRegistry providerRegistry;
    private final ModelRegistry modelRegistry;
    private final AiProviderRegistry aiProviderRegistry;

    @Autowired
    public CampusClawAiService(
            ApiProviderRegistry providerRegistry, ModelRegistry modelRegistry, AiProviderRegistry aiProviderRegistry) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry must not be null");
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry must not be null");
        this.aiProviderRegistry = Objects.requireNonNull(aiProviderRegistry, "aiProviderRegistry must not be null");
    }

    public CampusClawAiService(ApiProviderRegistry providerRegistry, ModelRegistry modelRegistry) {
        this(providerRegistry, modelRegistry, new AiProviderRegistry(List.of()));
    }

    /**
     * 使用完整选项发起流式模型调用。
     *
     * @param model 要调用的模型
     * @param context 对话上下文
     * @param options 流式选项；为空时使用默认值
     * @return Assistant 消息事件流
     * @throws IllegalArgumentException 没有可用 Provider 时抛出
     */
    public AssistantMessageEventStream stream(Model model, Context context, @Nullable StreamOptions options) {
        var managed = resolveManagedProvider(model);
        if (managed != null) {
            return managed.streamSimple(model, context, options == null ? null : SimpleStreamOptions.from(options));
        }
        var provider = resolveProvider(model);
        return provider.stream(model, context, options);
    }

    /**
     * 使用包含推理配置的简化选项发起流式模型调用。
     *
     * @param model 要调用的模型
     * @param context 对话上下文
     * @param options 简化流式选项；为空时使用默认值
     * @return Assistant 消息事件流
     * @throws IllegalArgumentException 没有可用 Provider 时抛出
     */
    public AssistantMessageEventStream streamSimple(
            Model model, Context context, @Nullable SimpleStreamOptions options) {
        var managed = resolveManagedProvider(model);
        if (managed != null) {
            return managed.streamSimple(model, context, options);
        }
        var provider = resolveProvider(model);
        return provider.streamSimple(model, context, options);
    }

    /**
     * 消费完整选项调用的事件流并返回最终 Assistant 消息。
     *
     * @param model 要调用的模型
     * @param context 对话上下文
     * @param options 流式选项；为空时使用默认值
     * @return 最终 Assistant 消息 Mono
     */
    public Mono<AssistantMessage> complete(Model model, Context context, @Nullable StreamOptions options) {
        return stream(model, context, options).result();
    }

    /**
     * 消费简化选项调用的事件流并返回最终 Assistant 消息。
     *
     * @param model 要调用的模型
     * @param context 对话上下文
     * @param options 简化流式选项；为空时使用默认值
     * @return 最终 Assistant 消息 Mono
     */
    public Mono<AssistantMessage> completeSimple(Model model, Context context, @Nullable SimpleStreamOptions options) {
        return streamSimple(model, context, options).result();
    }

    /**
     * 发送单条用户文本并返回完整 Assistant 响应。
     *
     * @param model 要调用的模型
     * @param userMessage 用户文本
     * @return 最终 Assistant 消息 Mono
     */
    public Mono<AssistantMessage> complete(Model model, String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        var context = new Context(null, List.of(new UserMessage(userMessage, System.currentTimeMillis())), null);
        return complete(model, context, null);
    }

    /**
     * 获取兼容的 API Provider 注册表。
     *
     * @return 当前 API Provider 注册表
     */
    public ApiProviderRegistry getProviderRegistry() {
        return providerRegistry;
    }

    /**
     * 获取模型注册表。
     *
     * @return 当前模型注册表
     */
    public ModelRegistry getModelRegistry() {
        return modelRegistry;
    }

    private ApiProvider resolveProvider(Model model) {
        Objects.requireNonNull(model, "model must not be null");
        return providerRegistry
                .getProvider(model.api())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ApiProvider registered for API: " + model.api().value()));
    }

    private com.campusclaw.ai.provider.AiProvider resolveManagedProvider(Model model) {
        Objects.requireNonNull(model, "model must not be null");
        return aiProviderRegistry
                .getProvider(new ProviderId(model.provider().value()))
                .orElse(null);
    }
}
