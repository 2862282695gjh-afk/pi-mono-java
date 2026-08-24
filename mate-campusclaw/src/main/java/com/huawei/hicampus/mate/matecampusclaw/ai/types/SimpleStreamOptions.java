/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.types;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.annotation.Nullable;

/**
 * 在基础流式选项上增加推理和工具选择配置。
 *
 * @param temperature 采样温度
 * @param maxTokens 最大生成 Token 数
 * @param apiKey 本次请求覆盖使用的 API Key
 * @param transport 传输协议
 * @param cacheRetention Prompt 缓存保留策略
 * @param sessionId 有状态对话 Session 标识
 * @param headers 请求附加 HTTP Header
 * @param maxRetryDelayMs 最大重试等待毫秒数
 * @param metadata 请求附加元数据
 * @param reasoning 模型推理级别
 * @param thinkingBudgets 各推理级别的 Token 预算
 * @param toolChoice 模型是否可以选择函数工具
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record SimpleStreamOptions(
        @JsonProperty("temperature") @Nullable Double temperature,
        @JsonProperty("maxTokens") @Nullable Integer maxTokens,
        @JsonProperty("apiKey") @Nullable String apiKey,
        @JsonProperty("transport") @Nullable Transport transport,
        @JsonProperty("cacheRetention") @Nullable CacheRetention cacheRetention,
        @JsonProperty("sessionId") @Nullable String sessionId,
        @JsonProperty("headers") @Nullable Map<String, String> headers,
        @JsonProperty("maxRetryDelayMs") @Nullable Long maxRetryDelayMs,
        @JsonProperty("metadata") @Nullable Map<String, Object> metadata,
        @JsonProperty("reasoning") @Nullable ThinkingLevel reasoning,
        @JsonProperty("thinkingBudgets") @Nullable ThinkingBudgets thinkingBudgets,
        @JsonProperty("toolChoice") @Nullable ToolChoice toolChoice) {

    /**
     * 创建所有字段均为空的简化流式选项。
     *
     * @return 空的简化流式选项
     */
    public static SimpleStreamOptions empty() {
        return new SimpleStreamOptions(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 创建 Builder。
     *
     * @return 空 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从基础流式选项创建不含推理配置的简化选项。
     *
     * @param base 要复制的基础流式选项
     * @return 复制基础字段后的简化流式选项
     */
    public static SimpleStreamOptions from(StreamOptions base) {
        return new SimpleStreamOptions(
                base.temperature(),
                base.maxTokens(),
                base.apiKey(),
                base.transport(),
                base.cacheRetention(),
                base.sessionId(),
                base.headers(),
                base.maxRetryDelayMs(),
                base.metadata(),
                null,
                null,
                null);
    }

    /**
     * 提取不含推理字段的基础流式选项。
     *
     * @return 基础流式选项
     */
    public StreamOptions toStreamOptions() {
        return new StreamOptions(
                temperature,
                maxTokens,
                apiKey,
                transport,
                cacheRetention,
                sessionId,
                headers,
                maxRetryDelayMs,
                metadata);
    }

    /**
     * 创建预填充当前字段的 Builder。
     *
     * @return 预填充 Builder
     */
    public Builder toBuilder() {
        return new Builder()
                .temperature(temperature)
                .maxTokens(maxTokens)
                .apiKey(apiKey)
                .transport(transport)
                .cacheRetention(cacheRetention)
                .sessionId(sessionId)
                .headers(headers)
                .maxRetryDelayMs(maxRetryDelayMs)
                .metadata(metadata)
                .reasoning(reasoning)
                .thinkingBudgets(thinkingBudgets)
                .toolChoice(toolChoice);
    }

    @SuppressWarnings("checkstyle:top_class_comment")
    public static final class Builder {
        private Double temperature;
        private Integer maxTokens;
        private String apiKey;
        private Transport transport;
        private CacheRetention cacheRetention;
        private String sessionId;
        private Map<String, String> headers;
        private Long maxRetryDelayMs;
        private Map<String, Object> metadata;
        private ThinkingLevel reasoning;
        private ThinkingBudgets thinkingBudgets;
        private ToolChoice toolChoice;

        Builder() {}

        public Builder temperature(@Nullable Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(@Nullable Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder apiKey(@Nullable String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder transport(@Nullable Transport transport) {
            this.transport = transport;
            return this;
        }

        public Builder cacheRetention(@Nullable CacheRetention cacheRetention) {
            this.cacheRetention = cacheRetention;
            return this;
        }

        public Builder sessionId(@Nullable String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder headers(@Nullable Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder maxRetryDelayMs(@Nullable Long maxRetryDelayMs) {
            this.maxRetryDelayMs = maxRetryDelayMs;
            return this;
        }

        public Builder metadata(@Nullable Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder reasoning(@Nullable ThinkingLevel reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder thinkingBudgets(@Nullable ThinkingBudgets thinkingBudgets) {
            this.thinkingBudgets = thinkingBudgets;
            return this;
        }

        public Builder toolChoice(@Nullable ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public SimpleStreamOptions build() {
            return new SimpleStreamOptions(
                    temperature,
                    maxTokens,
                    apiKey,
                    transport,
                    cacheRetention,
                    sessionId,
                    headers,
                    maxRetryDelayMs,
                    metadata,
                    reasoning,
                    thinkingBudgets,
                    toolChoice);
        }
    }
}
