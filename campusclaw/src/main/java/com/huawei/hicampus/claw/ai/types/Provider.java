/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.types;

import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 定义大模型 Provider 标识。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public enum Provider {
    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    MISTRAL("mistral"),
    AZURE_OPENAI("azure-openai-responses"),
    OPENAI_CODEX("openai-codex"),
    ZAI("zai"),
    KIMI_CODING("kimi-coding"),
    MINIMAX("minimax"),
    MINIMAX_CN("minimax-cn"),
    GITHUB_COPILOT("github-copilot"),
    XAI("xai"),
    GROQ("groq"),
    CEREBRAS("cerebras"),
    OPENROUTER("openrouter"),
    VERCEL_AI_GATEWAY("vercel-ai-gateway"),
    HUGGINGFACE("huggingface"),
    OPENCODE("opencode"),
    MATE_MODEL_MANAGER("mate-model-manager"),
    CUSTOM("custom");

    private final String value;

    Provider(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Provider fromValue(String value) {
        for (var p : values()) {
            if (p.value.equals(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown Provider: " + value);
    }

    /**
     * 宽松解析用户提供的 Provider 标识。
     *
     * <p>未知、空白或空值返回空结果；匹配时忽略大小写，并把下划线与连字符视为等价。
     *
     * @param value 用户提供的 Provider 标识
     * @return 匹配的 Provider；无法识别时为空
     */
    public static Optional<Provider> tryFromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
        for (var p : values()) {
            if (p.value.toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
