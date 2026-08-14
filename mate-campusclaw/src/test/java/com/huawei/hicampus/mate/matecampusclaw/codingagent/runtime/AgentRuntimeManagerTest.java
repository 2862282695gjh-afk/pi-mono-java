/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillFile;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.runtime.MateServiceClient.SkillReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeManagerTest {

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
    void resolvesEachSkillAndMaterializesCompleteRuntime() throws Exception {
        AgentRuntime runtime = runtime(List.of(new SkillReference("skill-1", "1"), new SkillReference("skill-2", "2")));
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime);
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));
        when(client.querySkillInfo("skill-2")).thenReturn(List.of(skillInfo("skill-b", "skill-2", "2")));

        PreparedAgentRuntime prepared = manager.prepare("agent-a");

        Path skillRoot = prepared.agentRoot().resolve(".campusclaw/skills/skill-a");
        assertTrue(Files.readString(skillRoot.resolve("SKILL.md"), StandardCharsets.UTF_8)
                .contains("name: skill-a"));
        assertEquals("Reference body", Files.readString(skillRoot.resolve("references/guide.md")));
        assertEquals("Template body", Files.readString(skillRoot.resolve("templates/request.txt")));
        var toolsJson = new ObjectMapper()
                .readTree(skillRoot.resolve("references/tools.json").toFile());
        assertEquals(3, toolsJson.path("tools").size());
        assertEquals("calendar", toolsJson.path("tools").get(0).path("tool_id").asText());
        assertEquals("calendar", toolsJson.path("tools").get(0).path("name").asText());
        assertEquals(
                "calendar", toolsJson.path("tools").get(0).path("description").asText());
        assertEquals("delete", toolsJson.path("tools").get(1).path("tool_id").asText());
        assertEquals("delete", toolsJson.path("tools").get(1).path("name").asText());
        assertEquals("delete", toolsJson.path("tools").get(1).path("description").asText());
        assertEquals("approval", toolsJson.path("tools").get(2).path("name").asText());
        assertTrue(Files.isRegularFile(skillRoot.resolve("skill.json")));
        assertTrue(Files.isRegularFile(prepared.agentRoot().resolve(".campusclaw/skills/skill-b/SKILL.md")));
        assertEquals("skill-a", prepared.skills().getFirst().name());
        verify(client).querySkillInfo("skill-1");
        verify(client).querySkillInfo("skill-2");
    }

    @Test
    void completeLocalRuntimeDoesNotRepeatRemoteQueries() {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime first = manager.prepare("agent-a");
        PreparedAgentRuntime second = manager.prepare("agent-a");

        assertEquals(first.agentRoot(), second.agentRoot());
        assertEquals(first.skills(), second.skills());
        verify(client, times(1)).getAgentRuntime("agent-a");
        verify(client, times(1)).querySkillInfo("skill-1");
    }

    @Test
    void rejectsLocalCacheWhenDeclaredResourceIsMissing() throws Exception {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime prepared = manager.prepare("agent-a");
        Files.delete(prepared.agentRoot().resolve(".campusclaw/skills/skill-a/references/guide.md"));

        AgentRuntimeException error = assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));

        assertTrue(error.getMessage().contains("incomplete"));
    }

    @Test
    void rejectsLocalCacheWhenSkillInstructionsAreModified() throws Exception {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime prepared = manager.prepare("agent-a");
        Path skillFile = prepared.agentRoot().resolve(".campusclaw/skills/skill-a/SKILL.md");
        Files.writeString(skillFile, Files.readString(skillFile) + "\nUntrusted instruction\n");

        AgentRuntimeException error = assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));

        assertTrue(error.getMessage().contains("incomplete"));
    }

    @Test
    void rejectsLocalCacheWhenToolsSnapshotIsModified() throws Exception {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime prepared = manager.prepare("agent-a");
        Path toolsFile = prepared.agentRoot().resolve(".campusclaw/skills/skill-a/references/tools.json");
        Files.writeString(toolsFile, "{\"tools\":[]}");

        AgentRuntimeException error = assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));

        assertTrue(error.getMessage().contains("incomplete"));
    }

    @Test
    void rejectsLocalCacheWithExtraSkillDirectory() throws Exception {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime prepared = manager.prepare("agent-a");
        Files.createDirectories(prepared.agentRoot().resolve(".campusclaw/skills/unbound-skill"));

        AgentRuntimeException error = assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));

        assertTrue(error.getMessage().contains("incomplete"));
    }

    @Test
    void failsClosedWhenSkillInfoResultIsEmpty() {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of());

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
        assertFalse(Files.exists(tempDir.resolve("agent/agent-a")));
    }

    @Test
    void failsClosedWhenSkillInfoReturnsMultipleEntries() {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo(), skillInfo()));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
        assertFalse(Files.exists(tempDir.resolve("agent/agent-a")));
    }

    @Test
    void failsClosedWhenSkillVersionDoesNotMatchBinding() {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "2"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
        assertFalse(Files.exists(tempDir.resolve("agent/agent-a")));
    }

    @Test
    void rejectsResourcePathTraversal() {
        SkillInfo unsafe = skillInfo(List.of(new SkillFile("reference-1", "../escape", "bad", "md")), List.of());
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(unsafe));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
        assertFalse(Files.exists(tempDir.resolve("escape.md")));
        assertFalse(Files.exists(tempDir.resolve("agent/agent-a")));
    }

    @Test
    void rejectsOversizedSkillResource() {
        SkillInfo oversized = skillInfo(
                List.of(new SkillFile("reference-1", "guide", "x".repeat(1024 * 1024 + 1), "md")), List.of());
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(oversized));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
        assertFalse(Files.exists(tempDir.resolve("agent/agent-a")));
    }

    @Test
    void rejectsTooManyBoundSkills() {
        List<SkillReference> skills = IntStream.range(0, 129)
                .mapToObj(index -> new SkillReference("skill-" + index, "1"))
                .toList();
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(skills));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
        assertFalse(Files.exists(tempDir.resolve("agent/agent-a")));
    }

    @Test
    void loadsAllSkillToolsFromLocalSnapshot() {
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime prepared = manager.prepare("agent-a");

        assertEquals(List.of("calendar", "delete", "approval"), manager.loadSkillToolNames(prepared, "skill-a"));
    }

    @Test
    void excludesUnsafeAgentToolsEvenWhenAllowedByMetadata() {
        AgentRuntime metadata = new AgentRuntime(
                "gpt-4o",
                List.of(),
                List.of(
                        tool("read", "allow"),
                        tool("BASH", "allow"),
                        tool("write", "allow"),
                        tool("edit", "allow"),
                        tool("EditDiff", "allow"),
                        tool("spawn_agent", "allow")),
                "Agent description",
                "Agent A",
                "agent-a",
                "agent-a",
                "Agent system prompt",
                List.of("campus"),
                "1",
                null);

        PreparedAgentRuntime prepared = new PreparedAgentRuntime("agent-a", tempDir, metadata, List.of());

        assertEquals(List.of("read"), prepared.allowedAgentToolNames());
    }

    @Test
    void includesAllSkillToolsRegardlessOfPermission() {
        List<String> names = List.of("calendar", "BASH", "write", "edit", "EditDiff", "spawn_agent");
        SkillInfo skill = new SkillInfo(
                "skill-a",
                "skill-1",
                "1",
                "Calendar workflow",
                "booking",
                List.of(
                        tool("calendar", "allow"),
                        tool("BASH", "deny"),
                        tool("write", "ask"),
                        tool("edit", "deny"),
                        tool("EditDiff", "ask"),
                        tool("spawn_agent", "allow")),
                List.of(),
                List.of(),
                List.of());
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(skill));

        PreparedAgentRuntime prepared = manager.prepare("agent-a");

        assertEquals(names, manager.loadSkillToolNames(prepared, "skill-a"));
    }

    @Test
    void rejectsDuplicateSkillTools() {
        SkillInfo duplicate = new SkillInfo(
                "skill-a",
                "skill-1",
                "1",
                "Calendar workflow",
                "booking",
                List.of(tool("calendar", "allow"), tool("calendar", "allow")),
                List.of(),
                List.of(),
                List.of());
        when(client.getAgentRuntime("agent-a")).thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));
        when(client.querySkillInfo("skill-1")).thenReturn(List.of(duplicate));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
    }

    @Test
    void rejectsAgentPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> manager.prepare("../agent-a"));
    }

    @Test
    void rejectsExistingIncompleteAgentInsteadOfMixingVersions() throws Exception {
        Files.createDirectories(tempDir.resolve("agent/agent-a/.campusclaw/skills"));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-a"));
        verify(client, times(0)).getAgentRuntime("agent-a");
    }

    private static AgentRuntime runtime(List<SkillReference> skills) {
        return new AgentRuntime(
                "gpt-4o",
                skills,
                List.of(tool("read", "allow"), tool("bash", "deny")),
                "Agent description",
                "Agent A",
                "agent-a",
                "agent-a",
                "Agent system prompt",
                List.of("campus"),
                "1",
                null);
    }

    private static SkillInfo skillInfo() {
        return skillInfo(
                List.of(new SkillFile("reference-1", "guide", "Reference body", "md")),
                List.of(new SkillFile("template-1", "request", "Template body", "txt")));
    }

    private static SkillInfo skillInfo(String name, String id, String version) {
        return new SkillInfo(
                name, id, version, "Secondary workflow", "secondary", List.of(), List.of(), List.of(), List.of());
    }

    private static SkillInfo skillInfo(List<SkillFile> references, List<SkillFile> templates) {
        return new SkillInfo(
                "skill-a",
                "skill-1",
                "1",
                "Calendar workflow",
                "booking",
                List.of(tool("calendar", "allow"), tool("delete", "deny"), tool("approval", "ask")),
                List.of(),
                templates,
                references);
    }

    private static BoundTool tool(String name, String permission) {
        return new BoundTool(name, name, name, "true", name, permission, "local", "1");
    }
}
