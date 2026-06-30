/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.mode.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolCatalogSnapshot;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolContribution;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolContributionSource;
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
                null,
                false,
                false);

        Map<String, Object> status = pool.toolStatus();

        assertThat(status).containsEntry("status", "ok");
        assertThat(status).containsEntry("activeSessions", 0);
        assertThat(status).containsEntry("version", catalog.snapshot().version());
        assertThat(status.get("tools")).asList().containsExactly("read");
        assertThat(status.get("diagnostics")).asList().isEmpty();
    }

    @Test
    void reloadToolsUsesLatestSettingsForCatalogContext() {
        var projectTool = new TestTool("project_tool");
        var catalog = new DefaultToolCatalog(List.of(context -> context.projectToolsEnabled()
                ? List.of(ToolContribution.add(projectTool, ToolContributionSource.project("project-tools"), 400))
                : List.of()));
        var settingsManager = mock(SettingsManager.class);
        when(settingsManager.load()).thenReturn(settingsWithProjectToolsDisabled());
        var pool = new SessionPool(
                null,
                null,
                null,
                List.of(projectTool),
                catalog,
                ToolSelection.all(),
                new SessionConfig(null, Path.of("/tmp/project"), null, "server"),
                null,
                false,
                false,
                settingsManager);

        Map<String, Object> status = pool.reloadTools();

        assertThat(status.get("tools")).asList().isEmpty();
    }

    @Test
    void reloadToolsRefreshesCatalogOnlyOnceWhenSessionsAreActive() throws Exception {
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
                null,
                false,
                false);
        var session = mock(AgentSession.class);
        activeSessions(pool).put("conversation", new SessionPool.Entry(session, System.currentTimeMillis()));

        Map<String, Object> status = pool.reloadTools();

        assertThat(catalog.refreshCount).hasValue(1);
        assertThat(status).containsEntry("version", 2L);
        verify(session).reloadFromCatalogSnapshot();
        verify(session, never()).reload();
    }

    @SuppressWarnings("unchecked")
    private Map<String, SessionPool.Entry> activeSessions(SessionPool pool) throws Exception {
        Field field = SessionPool.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return (Map<String, SessionPool.Entry>) field.get(pool);
    }

    private Settings settingsWithProjectToolsDisabled() {
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
                new Settings.ToolsSettings(null, null, null, null, false, true, true, null, true, Map.of()));
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
        public ToolCatalogSnapshot snapshot() {
            return snapshotForVersion(version);
        }

        @Override
        public ToolCatalogSnapshot refresh() {
            refreshCount.incrementAndGet();
            version++;
            return snapshot();
        }

        @Override
        public ToolCatalogSnapshot refresh(ToolRefreshRequest request) {
            return refresh();
        }

        @Override
        public List<AgentTool> resolve(ToolSelection selection) {
            return List.of(tool);
        }

        @Override
        public Runnable addChangeListener(com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog.ToolChangeListener listener) {
            return () -> {};
        }

        private ToolCatalogSnapshot snapshotForVersion(long snapshotVersion) {
            var tools = new LinkedHashMap<String, AgentTool>();
            tools.put(tool.name(), tool);
            return new ToolCatalogSnapshot(snapshotVersion, tools, Map.of(), List.of());
        }
    }
}
