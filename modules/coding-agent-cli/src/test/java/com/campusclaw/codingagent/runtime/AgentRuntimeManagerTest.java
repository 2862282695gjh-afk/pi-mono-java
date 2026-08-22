/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import com.campusclaw.codingagent.runtime.MateServiceClient.AgentReference;
import com.campusclaw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.campusclaw.codingagent.runtime.MateServiceClient.BoundTool;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillFile;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillReference;
import com.campusclaw.codingagent.session.SessionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeManagerTest {

    private static final String SKILL_ONE_ID = "skill-11111111111111111111111111111111";

    private static final String SKILL_TWO_ID = "skill-22222222222222222222222222222222";

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
        AgentRuntime runtime =
                runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"), new SkillReference(SKILL_TWO_ID, "2")));
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(runtime);
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));
        when(client.querySkillInfo(SKILL_TWO_ID)).thenReturn(List.of(skillInfo("skill-b", SKILL_TWO_ID, "2")));

        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        Path skillRoot = prepared.agentRoot().resolve(".campusclaw/skills/skill-a");
        assertTrue(Files.readString(skillRoot.resolve("SKILL.md"), StandardCharsets.UTF_8)
                .contains("name: skill-a"));
        assertEquals("Reference body", Files.readString(skillRoot.resolve("references/guide.md")));
        assertEquals("Template body", Files.readString(skillRoot.resolve("templates/request.txt")));
        assertEquals(
                "Agent system prompt", Files.readString(prepared.agentRoot().resolve(".campusclaw/systemPrompt.md")));
        var toolsJson = new ObjectMapper()
                .readTree(skillRoot.resolve("references/tools.json").toFile());
        assertEquals(3, toolsJson.path("tools").size());
        assertEquals(
                toolId("calendar"),
                toolsJson.path("tools").get(0).path("tool_id").asText());
        assertEquals("calendar", toolsJson.path("tools").get(0).path("name").asText());
        assertEquals(
                "calendar", toolsJson.path("tools").get(0).path("description").asText());
        assertEquals(
                toolId("delete"), toolsJson.path("tools").get(1).path("tool_id").asText());
        assertEquals("delete", toolsJson.path("tools").get(1).path("name").asText());
        assertEquals(
                "delete", toolsJson.path("tools").get(1).path("description").asText());
        assertEquals("approval", toolsJson.path("tools").get(2).path("name").asText());
        assertFalse(Files.exists(skillRoot.resolve("skill.json")));
        String skillFile = Files.readString(skillRoot.resolve("SKILL.md"), StandardCharsets.UTF_8);
        assertFalse(skillFile.contains("id:"));
        assertTrue(skillFile.contains("name: skill-a"));
        assertTrue(Files.isRegularFile(prepared.agentRoot().resolve(".campusclaw/skills/skill-b/SKILL.md")));
        assertEquals("skill-a", prepared.skills().getFirst().name());
        assertNull(prepared.skills().getFirst().id());
        assertEquals("Calendar workflow", prepared.skills().getFirst().description());
        verify(client).querySkillInfo(SKILL_ONE_ID);
        verify(client).querySkillInfo(SKILL_TWO_ID);
    }

    @Test
    void completeLocalRuntimeDoesNotRepeatRemoteQueries() {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"))));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime first = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        PreparedAgentRuntime second = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(first.agentRoot(), second.agentRoot());
        assertEquals(first.skills(), second.skills());
        verify(client, times(1)).getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verify(client, times(1)).querySkillInfo(SKILL_ONE_ID);
    }

    @Test
    void materializesModelSettingsFileWithDefaultModel() throws Exception {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(
                        List.of(new SkillReference(SKILL_ONE_ID, "1")), List.of("glm-5.2", "minimax-m2.5"), "3"));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        var settings = new ObjectMapper()
                .readTree(
                        prepared.agentRoot().resolve(".campusclaw/setting.json").toFile());
        assertEquals(
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                settings.path("agentId").asText());
        assertTrue(settings.path("agentVersion").isNumber());
        assertEquals(3, settings.path("agentVersion").asInt());
        assertEquals("glm-5.2", settings.path("defaultModel").asText());
        assertEquals(
                List.of("glm-5.2", "minimax-m2.5"),
                StreamSupport.stream(settings.path("enabledModels").spliterator(), false)
                        .map(node -> node.asText())
                        .toList());
    }

    @Test
    void keepsNonNumericAgentVersionAsStringInSettingsFile() throws Exception {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1")), List.of(), "1.0.0"));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        var settings = new ObjectMapper()
                .readTree(
                        prepared.agentRoot().resolve(".campusclaw/setting.json").toFile());
        assertTrue(settings.path("agentVersion").isTextual());
        assertEquals("1.0.0", settings.path("agentVersion").asText());
        assertTrue(settings.path("enabledModels").isArray());
        assertEquals(0, settings.path("enabledModels").size());
        assertTrue(settings.path("defaultModel").isMissingNode());
    }

    @Test
    void fallsBackToBaseModelWhenAgentBindsNoModel() throws Exception {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1")), List.of("  "), "1"));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime local = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        SessionConfig config =
                manager.sessionConfig(new SessionConfig("base-model", tempDir, "Base prompt", "interactive"), local);

        assertEquals("base-model", config.model());
    }

    @Test
    void persistsBindingAgentsInSnapshotAndReloadsFromLocalCache() throws Exception {
        List<AgentReference> bindings = List.of(
                new AgentReference(
                        "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "field-ops",
                        "Field Ops Agent",
                        "Handles on-site operations",
                        "2.0.0"),
                new AgentReference(
                        "agent-cccccccccccccccccccccccccccccccc",
                        "reporting",
                        "Reporting Agent",
                        "Writes diagnosis reports",
                        null));
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1")), bindings, null));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        PreparedAgentRuntime local = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(bindings, prepared.bindingAgents());
        assertEquals(bindings, local.bindingAgents());
        assertEquals(Boolean.TRUE, local.metadata().enabled());
        var persisted = new ObjectMapper()
                .readTree(
                        prepared.agentRoot().resolve(".campusclaw/agentId.json").toFile());
        assertEquals(
                "agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                persisted.path("bindingAgents").path(0).path("id").asText());
        assertEquals(
                "Handles on-site operations",
                persisted.path("bindingAgents").path(0).path("description").asText());
        verify(client, times(1)).getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void disabledAgentSnapshotReportsNotEnabled() {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1")), List.of(), Boolean.FALSE));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(Boolean.FALSE, prepared.metadata().enabled());
    }

    @Test
    void usesModifiedLocalSystemPromptWithoutRepeatingRemoteQueries() throws Exception {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"))));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Files.writeString(prepared.agentRoot().resolve(".campusclaw/systemPrompt.md"), "Modified prompt");

        PreparedAgentRuntime local = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        SessionConfig config =
                manager.sessionConfig(new SessionConfig("base-model", tempDir, "Base prompt", "interactive"), local);

        assertEquals("Modified prompt\n\nBase prompt", config.customPrompt());
        assertEquals("gpt-4o", config.model());
        verify(client, times(1)).getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verify(client, times(1)).querySkillInfo(SKILL_ONE_ID);
    }

    @Test
    void rematerializesWhenSnapshotCannotBeLoaded() throws Exception {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"))));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Files.delete(prepared.agentRoot().resolve(".campusclaw/skills/skill-a/SKILL.md"));

        PreparedAgentRuntime rematerialized = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertTrue(Files.isRegularFile(rematerialized.agentRoot().resolve(".campusclaw/skills/skill-a/SKILL.md")));
        assertEquals(1, rematerialized.skills().size());
        verify(client, times(2)).getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verify(client, times(2)).querySkillInfo(SKILL_ONE_ID);
    }

    @Test
    void rematerializesAndCleansStaleSkillDirectories() throws Exception {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"))));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));
        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Path rogueDir = prepared.agentRoot().resolve(".campusclaw/skills/rogue");
        Files.createDirectories(rogueDir);
        Files.writeString(rogueDir.resolve("SKILL.md"), "---\nname: rogue\ndescription: stale\n---\n");

        PreparedAgentRuntime rematerialized = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertFalse(Files.exists(rogueDir));
        assertEquals(
                List.of("skill-a"),
                rematerialized.skills().stream().map(SkillInfo::name).toList());
        verify(client, times(2)).getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void rejectsEmptySkillInfoResult() {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"))));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of());

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    void rejectsUntypedSkillIdFromRuntimeMetadata() {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference("skill-1", "1"))));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        verify(client, never()).querySkillInfo("skill-1");
    }

    @Test
    void rejectsAgentIdThatCouldEscapeTheAgentsRoot() {
        String[] invalidIds = {
            null,
            "",
            "   ",
            "agent-a",
            "skill-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "..",
            "../sibling",
            "a/b",
            "/etc/passwd",
            "a\\b",
            ".hidden",
            "-dash",
            "_under",
            "a?b"
        };
        for (String invalidId : invalidIds) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.prepare(invalidId),
                    "agentId must be rejected: " + invalidId);
        }
        verifyNoInteractions(client);
    }

    @Test
    void prepareCachedReadsLocalSnapshotWithoutRemoteCalls() {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(runtime(List.of()));
        manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        PreparedAgentRuntime cached = manager.prepareCached("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", cached.agentId());
        assertNull(manager.prepareCached("agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        verify(client, times(1)).getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verify(client, never()).getAgentRuntime("agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    @Test
    void coldStartOfOneAgentDoesNotBlockAnotherAgent() throws Exception {
        CountDownLatch agentAEntered = new CountDownLatch(1);
        CountDownLatch releaseAgentA = new CountDownLatch(1);
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenAnswer(invocation -> {
            agentAEntered.countDown();
            releaseAgentA.await();
            return runtime(List.of());
        });
        when(client.getAgentRuntime("agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")).thenReturn(runtime(List.of()));

        var agentAFuture =
                CompletableFuture.supplyAsync(() -> manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        try {
            assertThat(agentAEntered.await(5, TimeUnit.SECONDS)).isTrue();

            PreparedAgentRuntime agentB = manager.prepare("agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

            assertEquals("agent-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", agentB.agentId());
        } finally {
            releaseAgentA.countDown();
        }
        assertEquals(
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                agentAFuture.get(5, TimeUnit.SECONDS).agentId());
    }

    @Test
    void loadsAllSkillToolsFromLocalSnapshot() {
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"))));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skillInfo()));

        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(List.of("calendar", "delete", "approval"), manager.loadSkillToolNames(prepared, "skill-a"));
    }

    @Test
    void includesAllSkillToolsRegardlessOfPermission() {
        List<String> names = List.of("calendar", "BASH", "write", "edit", "EditDiff", "spawn_agent");
        SkillInfo skill = new SkillInfo(
                "skill-a",
                SKILL_ONE_ID,
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
        when(client.getAgentRuntime("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .thenReturn(runtime(List.of(new SkillReference(SKILL_ONE_ID, "1"))));
        when(client.querySkillInfo(SKILL_ONE_ID)).thenReturn(List.of(skill));

        PreparedAgentRuntime prepared = manager.prepare("agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertEquals(names, manager.loadSkillToolNames(prepared, "skill-a"));
    }

    private static AgentRuntime runtime(List<SkillReference> skills) {
        return runtime(skills, List.of(), null, List.of("gpt-4o"), "1");
    }

    private static AgentRuntime runtime(
            List<SkillReference> skills, List<AgentReference> bindingAgents, Boolean enabled) {
        return runtime(skills, bindingAgents, enabled, List.of("gpt-4o"), "1");
    }

    private static AgentRuntime runtime(List<SkillReference> skills, List<String> bindingModels, String version) {
        return runtime(skills, List.of(), null, bindingModels, version);
    }

    private static AgentRuntime runtime(
            List<SkillReference> skills,
            List<AgentReference> bindingAgents,
            Boolean enabled,
            List<String> bindingModels,
            String version) {
        return new AgentRuntime(
                bindingModels,
                skills,
                List.of(tool("read", "allow"), tool("bash", "deny")),
                bindingAgents,
                List.of("Agent description"),
                "Agent A",
                enabled,
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "Agent system prompt",
                List.of("campus"),
                version);
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
                SKILL_ONE_ID,
                "1",
                "Calendar workflow",
                "booking",
                List.of(tool("calendar", "allow"), tool("delete", "deny"), tool("approval", "ask")),
                List.of(),
                templates,
                references);
    }

    private static BoundTool tool(String name, String permission) {
        return new BoundTool(name, name, toolId(name), "true", name, permission, "local", "1");
    }

    private static String toolId(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        return "tool-" + uuid.toString().replace("-", "");
    }
}
