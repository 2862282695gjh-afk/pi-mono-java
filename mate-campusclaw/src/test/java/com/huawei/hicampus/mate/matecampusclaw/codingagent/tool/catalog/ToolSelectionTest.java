/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.settings.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ToolSelectionTest {

    @Test
    void noToolsOverridesIncludes() {
        var catalog = catalog("read", "bash");

        assertThat(catalog.resolve(new ToolSelection(List.of("read"), List.of(), true)))
                .isEmpty();
    }

    @Test
    void includesKeepRequestedToolsInCatalogOrder() {
        var catalog = catalog("read", "bash", "edit");

        assertThat(catalog.resolve(ToolSelection.fromCli("edit, read", false)))
                .extracting(AgentTool::name)
                .containsExactly("read", "edit");
    }

    @Test
    void excludesRemoveVisibleTools() {
        var catalog = catalog("read", "bash", "edit");

        assertThat(catalog.resolve(new ToolSelection(List.of(), List.of("bash"), false)))
                .extracting(AgentTool::name)
                .containsExactly("read", "edit");
    }

    @Test
    void fromSettingsUsesConfiguredIncludeExcludeAndNoTools() {
        var tools = new Settings.ToolsSettings(List.of("read", "bash"), List.of("bash"), true, null, Map.of());

        ToolSelection selection = ToolSelection.fromSettings(tools);

        assertThat(selection.include()).containsExactly("read", "bash");
        assertThat(selection.exclude()).containsExactly("bash");
        assertThat(selection.noTools()).isTrue();
    }

    @Test
    void disabledToolsSettingsDisablesEffectiveToolSelection() {
        var tools = new Settings.ToolsSettings(false, List.of("read"), List.of(), false, null, Map.of());

        ToolSelection selection = ToolSelection.fromSettings(tools);

        assertThat(selection.include()).containsExactly("read");
        assertThat(selection.noTools()).isTrue();
    }

    @Test
    void refreshRequestMapsToolSettingsToSourceContext() {
        var tools = new Settings.ToolsSettings(null, null, null, false, false, false, false, null, false, Map.of());

        var context = new ToolRefreshRequest(java.nio.file.Path.of("project"), tools)
                .toSourceContext(ToolSourceContext.defaults());

        assertThat(context.projectToolsEnabled()).isFalse();
        assertThat(context.userToolsEnabled()).isFalse();
        assertThat(context.replacementEnabled()).isFalse();
        assertThat(context.mcpEnabled()).isFalse();
    }

    @Test
    void cliToolsOverrideSettingsDefaults() {
        ToolSelection settings = new ToolSelection(List.of("read"), List.of("bash"), false);

        ToolSelection selection = ToolSelection.fromCli("edit", false, settings);

        assertThat(selection.include()).containsExactly("edit");
        assertThat(selection.exclude()).isEmpty();
        assertThat(selection.noTools()).isFalse();
    }

    @Test
    void cliNoToolsOverridesSettingsAndCliIncludes() {
        ToolSelection settings = new ToolSelection(List.of("read"), List.of(), false);

        ToolSelection selection = ToolSelection.fromCli("edit", true, settings);

        assertThat(selection.include()).containsExactly("edit");
        assertThat(selection.noTools()).isTrue();
    }

    private DefaultToolCatalog catalog(String... names) {
        return new DefaultToolCatalog(List.of(new SpringAgentToolSource(java.util.Arrays.stream(names)
                .map(TestTool::new)
                .map(AgentTool.class::cast)
                .toList())));
    }

    private record TestTool(String name) implements AgentTool {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return "Test tool " + name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return MAPPER.createObjectNode().put("type", "object");
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            return new AgentToolResult(List.of(new TextContent(name)), null);
        }
    }
}
