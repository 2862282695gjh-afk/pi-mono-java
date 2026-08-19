/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.mode.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.AgentSession;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.session.SessionConfig;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.settings.Settings;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.settings.SettingsManager;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.DefaultToolCatalog;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.SpringAgentToolSource;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolCatalog;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolRefreshRequest;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolSelection;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class SessionPoolToolStatusTest {

    @Test
    void toolStatusIncludesCatalogSnapshotAndVisibleTools() {
        var read = new TestTool("read");
        var bash = new TestTool("bash");
        var catalog = new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(read, bash))));
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(read, bash),
                catalog,
                new ToolSelection(List.of("read"), List.of(), false),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                false);

        Map<String, Object> status = pool.toolStatus();

        assertThat(status).containsEntry("status", "ok");
        assertThat(status).containsEntry("activeSessions", 0);
        assertThat(status).containsEntry("version", catalog.snapshot().version());
        assertThat(status.get("tools")).asList().containsExactly("read");
        assertThat(status.get("diagnostics")).asList().isEmpty();
    }

    @Test
    void reloadToolsAppliesLatestSelectionToCatalogAndActiveSessions() throws Exception {
        var read = new TestTool("read");
        var bash = new TestTool("bash");
        var catalog = new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(read, bash))));
        var toolsSettings = new Settings.ToolsSettings(true, List.of("read", "bash"), List.of("read"), false);
        var settingsManager = mock(SettingsManager.class);
        when(settingsManager.load()).thenReturn(settingsWithTools(toolsSettings));
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(read, bash),
                catalog,
                ToolSelection.all(),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                false,
                settingsManager,
                null,
                null,
                ToolSelection::fromSettings);
        var session = mock(AgentSession.class);
        when(session.reloadToolsWhenIdle()).thenReturn(true);
        activeSessions(pool).put("conversation", new SessionPool.Entry(session, System.currentTimeMillis()));

        Map<String, Object> status = pool.reloadTools();

        var expectedSelection = new ToolSelection(List.of("read", "bash"), List.of("read"), false);
        assertThat(status.get("tools")).asList().containsExactly("bash");
        verify(session).setToolSelection(expectedSelection);
        verify(session).reloadToolsWhenIdle();
    }

    @Test
    void reloadToolsKeepsCliSelectionPrecedenceOverLatestSettings() {
        var read = new TestTool("read");
        var bash = new TestTool("bash");
        var catalog = new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(read, bash))));
        var toolsSettings = new Settings.ToolsSettings(true, List.of("read"), List.of(), false);
        var settingsManager = mock(SettingsManager.class);
        when(settingsManager.load()).thenReturn(settingsWithTools(toolsSettings));
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(read, bash),
                catalog,
                ToolSelection.all(),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                false,
                settingsManager,
                null,
                null,
                latest -> ToolSelection.fromCli("bash", false, ToolSelection.fromSettings(latest)));

        Map<String, Object> status = pool.reloadTools();

        assertThat(status.get("tools")).asList().containsExactly("bash");
    }

    @Test
    void reloadToolsSerializesSettingsAndCatalogUpdate() throws Exception {
        var read = new TestTool("read");
        var bash = new TestTool("bash");
        var catalog = new DefaultToolCatalog(List.of(new SpringAgentToolSource(List.of(read, bash))));
        var settingsManager = mock(SettingsManager.class);
        var firstLoadEntered = new CountDownLatch(1);
        var releaseFirstLoad = new CountDownLatch(1);
        var secondLoadEntered = new CountDownLatch(1);
        configureOrderedSettingsLoads(settingsManager, firstLoadEntered, releaseFirstLoad, secondLoadEntered);
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(read, bash),
                catalog,
                ToolSelection.all(),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                false,
                settingsManager,
                null,
                null,
                ToolSelection::fromSettings);
        var executor = Executors.newFixedThreadPool(2);
        var secondReloadStarted = new CountDownLatch(1);
        try {
            var firstReload = executor.submit(pool::reloadTools);
            assertThat(firstLoadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var secondReload = executor.submit(() -> {
                secondReloadStarted.countDown();
                return pool.reloadTools();
            });
            assertThat(secondReloadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(secondLoadEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirstLoad.countDown();

            firstReload.get(5, TimeUnit.SECONDS);
            Map<String, Object> finalStatus = secondReload.get(5, TimeUnit.SECONDS);
            assertThat(finalStatus.get("tools")).asList().containsExactly("bash");
        } finally {
            releaseFirstLoad.countDown();
            executor.shutdownNow();
        }
    }

    private void configureOrderedSettingsLoads(
            SettingsManager settingsManager,
            CountDownLatch firstLoadEntered,
            CountDownLatch releaseFirstLoad,
            CountDownLatch secondLoadEntered) {
        var loadCount = new AtomicInteger();
        when(settingsManager.load()).thenAnswer(ignored -> {
            if (loadCount.incrementAndGet() == 1) {
                firstLoadEntered.countDown();
                if (!releaseFirstLoad.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the first settings load");
                }
                return settingsWithSelection("read");
            }
            secondLoadEntered.countDown();
            return settingsWithSelection("bash");
        });
    }

    private Settings settingsWithSelection(String toolName) {
        return settingsWithTools(new Settings.ToolsSettings(true, List.of(toolName), List.of(), false));
    }

    @Test
    void reloadToolsRestoresBaseCatalogAfterRefreshingSessions() throws Exception {
        var read = new TestTool("read");
        var catalog = new CountingToolCatalog(read);
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(read),
                catalog,
                ToolSelection.all(),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                false);
        var session = mock(AgentSession.class);
        activeSessions(pool).put("conversation", new SessionPool.Entry(session, System.currentTimeMillis()));

        Map<String, Object> status = pool.reloadTools();

        assertThat(catalog.refreshCount).hasValue(2);
        assertThat(status).containsEntry("version", 3L);
        verify(session).reloadToolsWhenIdle();
    }

    @Test
    void reloadToolsDefersManagedSessionUntilItsPromptFinishes() throws Exception {
        var read = new TestTool("read");
        var catalog = new CountingToolCatalog(read);
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(read),
                catalog,
                ToolSelection.all(),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                false);
        var session = mock(AgentSession.class);
        when(session.reloadToolsWhenIdle()).thenReturn(false);
        activeSessions(pool).put("agent-a\0conversation", new SessionPool.Entry(session, System.currentTimeMillis()));

        Map<String, Object> status = pool.reloadTools();

        assertThat(status).containsEntry("deferredSessions", 1);
        verify(session).reloadToolsWhenIdle();
        assertThat(catalog.refreshCount).hasValue(2);
    }

    @SuppressWarnings("unchecked")
    private Map<String, SessionPool.Entry> activeSessions(SessionPool pool) throws Exception {
        Field field = SessionPool.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return (Map<String, SessionPool.Entry>) field.get(pool);
    }

    private Settings settingsWithTools(Settings.ToolsSettings toolsSettings) {
        return new Settings(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                toolsSettings);
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

    private static final class CountingToolCatalog implements ToolCatalog {
        private final TestTool tool;
        private final AtomicInteger refreshCount = new AtomicInteger();
        private long version = 1L;

        private CountingToolCatalog(TestTool tool) {
            this.tool = tool;
        }

        @Override
        public ToolCatalog.Snapshot snapshot() {
            return snapshotForVersion(version);
        }

        @Override
        public ToolCatalog.Snapshot refresh() {
            refreshCount.incrementAndGet();
            version++;
            return snapshot();
        }

        @Override
        public ToolCatalog.Snapshot refresh(ToolRefreshRequest request) {
            return refresh();
        }

        @Override
        public List<AgentTool> resolve(ToolSelection selection) {
            return List.of(tool);
        }

        private ToolCatalog.Snapshot snapshotForVersion(long snapshotVersion) {
            var tools = new LinkedHashMap<String, AgentTool>();
            tools.put(tool.name(), tool);
            return new ToolCatalog.Snapshot(snapshotVersion, tools, Map.of(), List.of());
        }
    }
}
