/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void duplicateNameKeepsFirstToolAndReportsDiagnostic() {
        var first = new TestTool("read");
        var second = new TestTool("read");
        var catalog = new DefaultToolCatalog(List.of(
                context -> List.of(ToolContribution.add(first, ToolContributionSource.system("spring"))),
                context -> List.of(ToolContribution.add(second, ToolContributionSource.extension("dup")))));

        assertThat(catalog.resolve(ToolSelection.all())).containsExactly(first);
        assertThat(catalog.snapshot().diagnostics())
                .anySatisfy(message -> assertThat(message).contains("read", "ADD"));
    }

    @Test
    void refreshWithRequestUpdatesContextForSources() {
        var observed = new AtomicReference<ToolSource.Context>();
        ToolSource source = context -> {
            observed.set(context);
            return List.of();
        };
        var catalog = new DefaultToolCatalog(List.of(source), new ToolSource.Context(java.nio.file.Path.of("/base")));

        catalog.refresh(new ToolRefreshRequest(java.nio.file.Path.of("/next")));

        assertThat(observed.get()).isEqualTo(new ToolSource.Context(java.nio.file.Path.of("/next")));
    }

    @Test
    void sourceFailureKeepsPreviousSnapshotAndAppendsDiagnostic() {
        var read = new TestTool("read");
        ToolSource failing = context -> {
            throw new IllegalStateException("broken source");
        };
        var catalog = new DefaultToolCatalog(List.of(
                context -> List.of(ToolContribution.add(read, ToolContributionSource.system("spring"))), failing));
        assertThat(catalog.snapshot().degraded()).isTrue();
        long versionBefore = catalog.snapshot().version();

        ToolCatalog.Snapshot failed = catalog.refresh(new ToolRefreshRequest(java.nio.file.Path.of("/next")));

        assertThat(versionBefore).isEqualTo(1L);
        assertThat(failed.version()).isEqualTo(1L);
        assertThat(failed.degraded()).isTrue();
        assertThat(catalog.snapshot().toolsByName()).containsEntry("read", read);
        assertThat(failed.diagnostics())
                .anySatisfy(message -> assertThat(message).contains("broken source"));
        assertThat(catalog.resolve(ToolSelection.all())).containsExactly(read);
    }

    @Test
    void concurrentRefreshesAllocateDistinctVersions() throws Exception {
        var source = new BlockingSource();
        var catalog = new DefaultToolCatalog(List.of(source));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> catalog.refresh());
            assertThat(source.awaitFirstRefresh()).isTrue();
            var second = executor.submit(() -> catalog.refresh());
            source.release();

            assertThat(List.of(first.get().version(), second.get().version())).containsExactlyInAnyOrder(2L, 3L);
            assertThat(catalog.snapshot().version()).isEqualTo(3L);
        }
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

    private static final class BlockingSource implements ToolSource {
        private final CountDownLatch firstRefreshStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        private boolean awaitFirstRefresh() throws InterruptedException {
            return firstRefreshStarted.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        @Override
        public List<ToolContribution> load(ToolSource.Context context) {
            if (calls.incrementAndGet() == 1) {
                return List.of();
            }
            firstRefreshStarted.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
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
}
