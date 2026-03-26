package com.mariozechner.pi.ai.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * LLM provider identifier.
 */
public enum Provider {

    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    GOOGLE("google"),
    GOOGLE_VERTEX("google-vertex"),
    MISTRAL("mistral"),
    AMAZON_BEDROCK("amazon-bedrock"),
    AZURE_OPENAI("azure-openai-responses"),
    OPENAI_CODEX("openai-codex");

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
}
