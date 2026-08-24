/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillFile;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeManagerTest {

    private static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String CHILD_ID = "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final String SKILL_ID = "skill-11111111111111111111111111111111";

    @TempDir
    Path tempDir;

    private MateServiceClient client;

    private AgentRuntimeManager manager;

    @BeforeEach
    void setUp() {
        client = mock(MateServiceClient.class);
        var properties = new AgentRuntimeProperties(
                null, tempDir.resolve("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        manager = new AgentRuntimeManager(properties, client, new ObjectMapper());
    }

    @Test
    void coldPreparePublishesExactManagedLayoutWithoutToolsJson() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");

        PreparedAgentRuntime prepared = manager.prepare(AGENT_ID);

        Path managed = prepared.agentRoot().resolve(".campusclaw");
        assertTrue(Files.isRegularFile(managed.resolve("agent.json")));
        assertTrue(Files.isRegularFile(managed.resolve("settings.json")));
        assertEquals("prompt-v1", Files.readString(managed.resolve("SYSTEM.md")));
        assertTrue(Files.isRegularFile(managed.resolve("agents/researcher.json")));
        assertTrue(Files.isRegularFile(managed.resolve("skills/calendar/skill.json")));
        assertTrue(Files.isRegularFile(managed.resolve("skills/calendar/SKILL.md")));
        assertTrue(Files.isDirectory(managed.resolve("skills/calendar/references")));
        assertTrue(Files.isDirectory(managed.resolve("skills/calendar/templates")));
        try (var paths = Files.walk(managed)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().equals("tools.json")));
        }
        assertEquals(SKILL_ID, prepared.skillIdsByName().get("calendar"));
        assertEquals(CHILD_ID, prepared.childAgentsByName().get("researcher").id());
        assertTrue(prepared.childAgentsByName().get("researcher").enabled());
        assertTrue(new ObjectMapper()
                .readTree(managed.resolve("agents/researcher.json").toFile())
                .path("enabled")
                .asBoolean());
    }

    @Test
    void completeCacheAvoidsMateAndRefreshRebuildsIt() {
        stubRuntime("1.0.0", "prompt-v1");
        manager.prepare(AGENT_ID);
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime("2.0.0", "prompt-v2"));

        PreparedAgentRuntime cached = manager.prepare(AGENT_ID);
        assertEquals("prompt-v1", manager.readSystemPrompt(cached));
        PreparedAgentRuntime refreshed = manager.refresh(AGENT_ID);

        assertEquals("prompt-v2", manager.readSystemPrompt(refreshed));
        verify(client, times(2)).getAgentRuntime(AGENT_ID);
    }

    @Test
    void failedRefreshPreservesLastCompleteDirectory() {
        stubRuntime("1.0.0", "prompt-v1");
        manager.prepare(AGENT_ID);
        when(client.getAgentRuntime(AGENT_ID)).thenThrow(new AgentRuntimeException("Mate unavailable"));

        assertThrows(AgentRuntimeException.class, () -> manager.refresh(AGENT_ID));

        PreparedAgentRuntime cached = manager.prepareCached(AGENT_ID);
        assertEquals("prompt-v1", manager.readSystemPrompt(cached));
    }

    @Test
    void incompleteOrForbiddenCacheIsRebuilt() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        Path managed = first.agentRoot().resolve(".campusclaw");
        Files.delete(managed.resolve("SYSTEM.md"));

        manager.prepare(AGENT_ID);
        Files.writeString(managed.resolve("skills/calendar/references/tools.json"), "{}");
        manager.prepare(AGENT_ID);

        verify(client, times(3)).getAgentRuntime(AGENT_ID);
        assertFalse(Files.exists(managed.resolve("skills/calendar/references/tools.json")));
    }

    @Test
    void symbolicLinkInsideCacheForcesSafeRebuild() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        Path link = first.agentRoot().resolve(".campusclaw/skills/calendar/references/external.md");
        Files.createSymbolicLink(link, tempDir.resolve("outside"));

        manager.prepare(AGENT_ID);

        verify(client, times(2)).getAgentRuntime(AGENT_ID);
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void duplicateNamesFailBeforeReplacingPreviousSnapshot() {
        stubRuntime("1.0.0", "prompt-v1");
        manager.prepare(AGENT_ID);
        AgentReference first = child("researcher", CHILD_ID);
        AgentReference duplicate = child("Researcher", "agent-cccccccccccccccccccccccccccccccc");
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime(List.of(first, duplicate), "prompt-v2"));

        assertThrows(AgentRuntimeException.class, () -> manager.refresh(AGENT_ID));

        assertEquals("prompt-v1", manager.readSystemPrompt(manager.prepareCached(AGENT_ID)));
    }

    @Test
    void missingFixedChildVersionFailsBeforeReplacingPreviousSnapshot() {
        stubRuntime("1.0.0", "prompt-v1");
        manager.prepare(AGENT_ID);
        AgentReference invalid = new AgentReference(CHILD_ID, "researcher", "Child", "Researches", null);
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime(List.of(invalid), "prompt-v2"));

        assertThrows(AgentRuntimeException.class, () -> manager.refresh(AGENT_ID));

        assertEquals("prompt-v1", manager.readSystemPrompt(manager.prepareCached(AGENT_ID)));
    }

    @Test
    void prepareCachedNeverCallsMate() {
        assertNull(manager.prepareCached(AGENT_ID));
        verify(client, never()).getAgentRuntime(AGENT_ID);
    }

    @Test
    void rejectsAgentIdThatCouldEscapeRoot() {
        for (String invalid : List.of("agent-a", "../agent-a", "skill-11111111111111111111111111111111")) {
            assertThrows(IllegalArgumentException.class, () -> manager.prepare(invalid));
        }
    }

    private void stubRuntime(String version, String prompt) {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime(version, prompt));
        when(client.querySkillInfo(SKILL_ID)).thenReturn(List.of(skill()));
    }

    private static AgentRuntime runtime(String version, String prompt) {
        return runtime(List.of(child("researcher", CHILD_ID)), prompt, version);
    }

    private static AgentRuntime runtime(List<AgentReference> children, String prompt) {
        return runtime(children, prompt, "2.0.0");
    }

    private static AgentRuntime runtime(List<AgentReference> children, String prompt, String version) {
        return new AgentRuntime(
                List.of("gpt-4o"),
                List.of(new SkillReference(SKILL_ID, "1.0.0")),
                List.of(),
                children,
                List.of("description"),
                "Agent A",
                true,
                AGENT_ID,
                "agent-a",
                prompt,
                List.of(),
                version);
    }

    private static AgentReference child(String name, String id) {
        return new AgentReference(id, name, "Child", "Researches a task", "1.0.0");
    }

    private static SkillInfo skill() {
        return new SkillInfo(
                "calendar",
                SKILL_ID,
                "1.0.0",
                "Calendar workflow",
                "booking",
                List.of(),
                List.of(),
                List.of(new SkillFile("template-1", "request", "Template", "txt")),
                List.of(new SkillFile("reference-1", "guide", "Reference", "md")));
    }
}
