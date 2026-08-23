/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.campusclaw.ai.env.ProviderConfigResolver;
import com.campusclaw.ai.env.ResolvedProviderConfig;
import com.campusclaw.ai.model.ModelRegistry;
import com.campusclaw.ai.types.Api;
import com.campusclaw.ai.types.Model;
import com.campusclaw.ai.types.Provider;

import org.junit.jupiter.api.Test;

/**
 * {@link ModelCatalogService} 的凭据过滤和稳定排序测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
class ModelCatalogServiceTest {

    @Test
    void shouldReturnOnlyModelsWithCurrentCredentialsInStableOrder() {
        ModelRegistry registry = mock(ModelRegistry.class);
        ProviderConfigResolver resolver = mock(ProviderConfigResolver.class);
        Model openAi = model("z-model", Provider.OPENAI, null);
        Model anthropic = model("a-model", Provider.ANTHROPIC, null);
        Model unavailable = model("missing", Provider.MISTRAL, null);
        when(registry.getAllModels()).thenReturn(List.of(openAi, unavailable, anthropic));
        when(resolver.resolve(eq(Provider.OPENAI), any(Model.class)))
                .thenReturn(new ResolvedProviderConfig("openai-key", null, null));
        when(resolver.resolve(eq(Provider.ANTHROPIC), any(Model.class)))
                .thenReturn(new ResolvedProviderConfig("anthropic-key", null, null));
        when(resolver.resolve(eq(Provider.MISTRAL), any(Model.class)))
                .thenReturn(new ResolvedProviderConfig(null, null, null));

        var service = new ModelCatalogService(registry, resolver);

        assertThat(service.getAvailableModels()).containsExactly(anthropic, openAi);
    }

    @Test
    void shouldAcceptEmbeddedCredentialAndRejectResolverFailure() {
        ModelRegistry registry = mock(ModelRegistry.class);
        ProviderConfigResolver resolver = mock(ProviderConfigResolver.class);
        Model embedded = model("embedded", Provider.CUSTOM, "secret");
        Model failed = model("failed", Provider.OPENAI, null);
        when(registry.getAllModels()).thenReturn(List.of(failed, embedded));
        when(resolver.resolve(Provider.OPENAI, failed)).thenThrow(new IllegalStateException("unavailable"));

        var service = new ModelCatalogService(registry, resolver);

        assertThat(service.getAvailableModels()).containsExactly(embedded);
        assertThat(service.hasCredentials(null)).isFalse();
    }

    private static Model model(String id, Provider provider, String apiKey) {
        return new Model(
                id, id, Api.ANTHROPIC_MESSAGES, provider, null, false, List.of(), null, 0, 0, null, null, apiKey);
    }
}
