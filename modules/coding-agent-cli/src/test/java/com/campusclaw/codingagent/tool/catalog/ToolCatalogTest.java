/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.extension.Extension;
import com.campusclaw.codingagent.extension.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ToolCatalogTest {

    @Test
    void springSourcePassesBuiltInToolsThrough() {
        var read = new TestTool("read");
        var catalog = new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(read))));

        assertThat(catalog.resolve(ToolSelection.all())).containsExactly(read);
        assertThat(catalog.snapshot().toolsByName()).containsEntry("read", read);
    }

    @Test
    void extensionSourceContributesRegisteredTools() {
        var extensionRegistry = new ExtensionRegistry();
        var jira = new TestTool("jira_search");
        extensionRegistry.register(new TestExtension("jira", jira));
        var catalog = new DefaultToolCatalog(List.of(new ExtensionToolSource(extensionRegistry)));

        assertThat(catalog.resolve(ToolSelection.all())).containsExactly(jira);
        assertThat(catalog.snapshot().sourcesByName().get("jira_search").sourceId())
                .isEqualTo("extension:jira");
    }

    @Test
    void higherPriorityReplaceOverridesLowerPriorityTool() {
        var builtIn = new TestTool("read");
        var replacement = new TestTool("read");
        var catalog = new DefaultToolCatalog(List.of(
                context -> List.of(ToolContribution.add(builtIn, ToolContributionSource.system("spring"), 100)),
                context -> List.of(ToolContribution.replace(
                        replacement, ToolContributionSource.project("project-tools"), 400, "read"))));

        assertThat(catalog.resolve(ToolSelection.all())).containsExactly(replacement);
        assertThat(catalog.snapshot().diagnostics()).isEmpty();
    }

    @Test
    void addConflictKeepsExistingToolAndReportsDiagnostic() {
        var first = new TestTool("read");
        var second = new TestTool("read");
        var catalog = new DefaultToolCatalog(List.of(
                context -> List.of(ToolContribution.add(first, ToolContributionSource.system("spring"), 100)),
                context -> List.of(ToolContribution.add(second, ToolContributionSource.user("user-tools"), 300))));

        assertThat(catalog.resolve(ToolSelection.all())).containsExactly(first);
        assertThat(catalog.snapshot().diagnostics())
                .anySatisfy(message -> assertThat(message).contains("read", "ADD"));
    }

    @Test
    void disableHidesExistingTool() {
        var read = new TestTool("read");
        var catalog = new DefaultToolCatalog(List.of(
                context -> List.of(ToolContribution.add(read, ToolContributionSource.system("spring"), 100)),
                context -> List.of(
                        ToolContribution.disable("read", ToolContributionSource.project("project-tools"), 400))));

        assertThat(catalog.resolve(ToolSelection.all())).isEmpty();
        assertThat(catalog.snapshot().toolsByName()).doesNotContainKey("read");
    }

    @Test
    void wrapDecoratesExistingToolWithoutChangingToolNameOrSourceLayer() {
        var read = new TestTool("read");
        var catalog = new DefaultToolCatalog(List.of(
                context -> List.of(ToolContribution.add(read, ToolContributionSource.system("spring"), 100)),
                context -> List.of(ToolContribution.wrap(
                        "read", tool -> new WrappedTool(tool), ToolContributionSource.project("project-tools"), 400))));

        AgentTool wrapped = catalog.resolve(ToolSelection.all()).getFirst();

        assertThat(wrapped.name()).isEqualTo("read");
        assertThat(wrapped.description()).contains("wrapped");
        assertThat(catalog.snapshot().sourcesByName().get("read").sourceId()).isEqualTo("project-tools");
    }

    @Test
    void refreshNotifiesChangeListenersAfterSuccessfulSnapshotSwap() {
        var first = new TestTool("read");
        var second = new TestTool("bash");
        var source = new MutableSource(List.of(ToolContribution.add(first, ToolContributionSource.system("spring"), 100)));
        var catalog = new DefaultToolCatalog(List.of(source));
        var notification = new AtomicReference<ToolCatalogSnapshot>();
        catalog.addChangeListener((previous, current) -> notification.set(current));

        source.contributions = List.of(ToolContribution.add(second, ToolContributionSource.system("spring"), 100));
        ToolCatalogSnapshot refreshed = catalog.refresh();

        assertThat(notification.get()).isSameAs(refreshed);
        assertThat(refreshed.toolsByName()).containsOnlyKeys("bash");
    }

    private record TestExtension(String id, AgentTool tool) implements Extension {

        @Override
        public String name() {
            return id;
        }

        @Override
        public List<AgentTool> tools() {
            return List.of(tool);
        }
    }

    private static final class MutableSource implements ToolSource {
        private List<ToolContribution> contributions;

        private MutableSource(List<ToolContribution> contributions) {
            this.contributions = contributions;
        }

        @Override
        public List<ToolContribution> load(ToolSourceContext context) {
            return contributions;
        }
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

    private record WrappedTool(AgentTool delegate) implements AgentTool {

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String label() {
            return delegate.label();
        }

        @Override
        public String description() {
            return "wrapped " + delegate.description();
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return delegate.parameters();
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) throws Exception {
            return delegate.execute(toolCallId, params, signal, onUpdate);
        }
    }
}
