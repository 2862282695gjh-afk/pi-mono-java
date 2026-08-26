/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.annotation.Nullable;

/**
 * 表示大模型生成的 Assistant 消息。
 *
 * @param content 响应内容块
 * @param api 调用使用的 API 类型
 * @param provider 模型 Provider
 * @param model 请求使用的模型标识
 * @param responseId Provider 返回的可选响应标识
 * @param responseModel 上游返回的可选实际模型标识
 * @param usage Token 和费用用量
 * @param stopReason 模型停止生成的原因
 * @param errorCode 停止原因为错误时的可选稳定错误码
 * @param errorMessage 停止原因为错误时的可选消息
 * @param timestamp Unix 毫秒时间戳
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record AssistantMessage(
        @JsonProperty("content") List<ContentBlock> content,
        @JsonProperty("api") String api,
        @JsonProperty("provider") String provider,
        @JsonProperty("model") String model,
        @JsonProperty("responseId") @Nullable String responseId,
        @JsonProperty("responseModel") @Nullable String responseModel,
        @JsonProperty("usage") Usage usage,
        @JsonProperty("stopReason") StopReason stopReason,
        @JsonProperty("errorCode") @Nullable String errorCode,
        @JsonProperty("errorMessage") @Nullable String errorMessage,
        @JsonProperty("timestamp") long timestamp)
        implements Message {

    public AssistantMessage(
            List<ContentBlock> content,
            String api,
            String provider,
            String model,
            @Nullable String responseId,
            @Nullable String responseModel,
            Usage usage,
            StopReason stopReason,
            @Nullable String errorMessage,
            long timestamp) {
        this(
                content,
                api,
                provider,
                model,
                responseId,
                responseModel,
                usage,
                stopReason,
                null,
                errorMessage,
                timestamp);
    }

    public AssistantMessage(
            List<ContentBlock> content,
            String api,
            String provider,
            String model,
            @Nullable String responseId,
            Usage usage,
            StopReason stopReason,
            @Nullable String errorMessage,
            long timestamp) {
        this(content, api, provider, model, responseId, null, usage, stopReason, null, errorMessage, timestamp);
    }
}
