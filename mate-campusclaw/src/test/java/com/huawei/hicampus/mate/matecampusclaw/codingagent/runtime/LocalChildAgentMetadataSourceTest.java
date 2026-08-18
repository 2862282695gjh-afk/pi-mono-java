/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.AgentBindingResolver.ChildAgentMetadata;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalChildAgentMetadataSourceTest {

    @TempDir
    Path tempDir;

    private MateServiceClient client;
    private LocalChildAgentMetadataSource source;
    private Path agentsRoot;

    @BeforeEach
    void setUp() {
        client = mock(MateServiceClient.class);
        source = new LocalChildAgentMetadataSource(
                new AgentRuntimeProperties(null, tempDir, Duration.ofSeconds(1L), Duration.ofSeconds(1L)),
                client,
                new ObjectMapper());
        agentsRoot = tempDir;
    }

    @Test
    void readsLocalSnapshotFirstWithoutRemoteCall() throws Exception {
        writeSnapshot("agent-2", "2.1.0", true);

        Optional<ChildAgentMetadata> metadata = source.load("agent-2");

        assertTrue(metadata.isPresent());
        assertEquals("agent-2", metadata.get().agentId());
        assertEquals("2.1.0", metadata.get().version());
        assertTrue(metadata.get().enabled());
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void fallsBackToRemoteReadWhenNoLocalSnapshot() {
        when(client.getAgentRuntime("agent-4")).thenReturn(runtime("agent-4", "3", false));

        Optional<ChildAgentMetadata> metadata = source.load("agent-4");

        assertTrue(metadata.isPresent());
        assertEquals("3", metadata.get().version());
        org.mockito.Mockito.verify(client).getAgentRuntime("agent-4");
    }

    @Test
    void failsClosedToEmptyWhenLocalAndRemoteBothFail() throws Exception {
        writeCorruptSnapshot("agent-5");
        when(client.getAgentRuntime(anyString())).thenThrow(new AgentRuntimeException("remote unavailable"));

        assertTrue(source.load("agent-5").isEmpty());
    }

    private void writeSnapshot(String agentId, String version, Boolean enabled) throws Exception {
        Path file = agentsRoot.resolve(agentId).resolve(".campusclaw/agentId.json");
        Files.createDirectories(file.getParent());
        new ObjectMapper().writeValue(file.toFile(), runtime(agentId, version, enabled));
    }

    private void writeCorruptSnapshot(String agentId) throws Exception {
        Path file = agentsRoot.resolve(agentId).resolve(".campusclaw/agentId.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");
    }

    private static AgentRuntime runtime(String agentId, String version, Boolean enabled) {
        return new AgentRuntime(
                List.of("glm-5"),
                List.<SkillReference>of(),
                List.<BoundTool>of(),
                List.of(),
                List.of("d"),
                "n",
                enabled,
                agentId,
                agentId,
                "prompt",
                List.of("campus"),
                version);
    }
}
