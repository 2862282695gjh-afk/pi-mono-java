/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.ai.env;

import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.ai.types.Provider;

import org.springframework.stereotype.Service;

/**
 * Resolves API keys from environment variables for each provider.
 * Follows the same mapping as the TypeScript env-api-keys.ts.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/05/06]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Service
public class EnvApiKeyResolver {

    /**
     * Resolves an API key for the given provider from environment variables.
     *
     * @param provider the LLM provider
     * @return the API key if found, or empty
     */
    public Optional<String> resolve(Provider provider) {
        return switch (provider) {
            case ANTHROPIC -> firstEnv("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN");
            case OPENAI -> firstEnv("OPENAI_API_KEY");
            case AZURE_OPENAI -> firstEnv("AZURE_OPENAI_API_KEY");
            case MISTRAL -> firstEnv("MISTRAL_API_KEY");
            case OPENAI_CODEX -> firstEnv("OPENAI_API_KEY");
            case ZAI -> firstEnv("ZAI_API_KEY");
            case KIMI_CODING -> firstEnv("KIMI_API_KEY");
            case MINIMAX -> firstEnv("MINIMAX_API_KEY");
            case MINIMAX_CN -> firstEnv("MINIMAX_CN_API_KEY");
            case XAI -> firstEnv("XAI_API_KEY");
            case GROQ -> firstEnv("GROQ_API_KEY");
            case CEREBRAS -> firstEnv("CEREBRAS_API_KEY");
            case OPENROUTER -> firstEnv("OPENROUTER_API_KEY");
            case VERCEL_AI_GATEWAY -> firstEnv("AI_GATEWAY_API_KEY");
            case HUGGINGFACE -> firstEnv("HF_TOKEN");
            case GITHUB_COPILOT -> firstEnv("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN");
            case OPENCODE -> firstEnv("OPENCODE_API_KEY");
            default -> Optional.empty();
        };
    }

    /**
     * Returns the first non-null, non-blank value from the given env var names.
     *
     * @param names environment variable names to probe in order
     * @return the first defined non-blank value, or empty when none match
     */
    private Optional<String> firstEnv(String... names) {
        for (String name : names) {
            String val = System.getenv(name);
            if (val != null && !val.isBlank()) {
                return Optional.of(val);
            }
        }
        return Optional.empty();
    }
}
