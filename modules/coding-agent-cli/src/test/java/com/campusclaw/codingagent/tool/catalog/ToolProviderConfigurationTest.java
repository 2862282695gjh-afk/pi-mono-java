/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.ToolProvider;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ToolProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void toolProviderDelegatesAllowedToolsToCatalogSelection() {
        ToolCatalog catalog = mock(ToolCatalog.class);
        List<AgentTool> tools = List.of(mock(AgentTool.class));
        when(catalog.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(tools);
        ToolProvider provider = new ToolProviderConfiguration().toolProvider(catalog);

        List<AgentTool> resolved = provider.resolve(List.of("read", "bash"));

        var selection = ArgumentCaptor.forClass(ToolSelection.class);
        verify(catalog).resolve(selection.capture());
        assertThat(resolved).isSameAs(tools);
        assertThat(selection.getValue().include()).containsExactly("read", "bash");
        assertThat(selection.getValue().exclude()).isEmpty();
        assertThat(selection.getValue().noTools()).isFalse();
    }

    @Test
    void toolProviderDelegatesEmptyAllowedToolsToAllSelection() {
        ToolCatalog catalog = mock(ToolCatalog.class);
        ToolProvider provider = new ToolProviderConfiguration().toolProvider(catalog);

        provider.resolve(List.of());

        var selection = ArgumentCaptor.forClass(ToolSelection.class);
        verify(catalog).resolve(selection.capture());
        assertThat(selection.getValue()).isEqualTo(ToolSelection.all());
    }

    @Nested
    class SpringContext {

        @Test
        void createsDefaultToolCatalogWithAutowiredToolSources() {
            contextRunner
                    .withBean(DefaultToolCatalog.class)
                    .run(context -> assertThat(context).hasSingleBean(ToolCatalog.class));
        }
    }
}
