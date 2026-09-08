/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtime;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentReference;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.AgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillFile;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.SkillReference;
import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.RuntimeAgentPromptLoader;
import com.huawei.hicampus.claw.codingagent.skill.SkillLoadException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
        var properties =
                new AgentRuntimeProperties(tempDir.resolve("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        manager = new AgentRuntimeManager(properties, client, new ObjectMapper());
    }

    @Test
    void coldPreparePublishesExactManagedLayoutWithoutToolsJson() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");

        PreparedAgentRuntime prepared = manager.prepare(AGENT_ID);

        Path managed = prepared.agentRoot().resolve(".campusclaw");
        assertTrue(Files.isRegularFile(managed.resolve("agent.json")));
        assertTrue(Files.isRegularFile(managed.resolve("settings.json")));
        assertEquals("prompt-v1", Files.readString(managed.resolve("SYSTEM.md"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(managed.resolve("agents/researcher.json")));
        assertTrue(Files.isRegularFile(managed.resolve("skills/calendar/skill.json")));
        assertTrue(Files.isRegularFile(managed.resolve("skills/calendar/SKILL.md")));
        assertEquals(
                skillContent(), Files.readString(managed.resolve("skills/calendar/SKILL.md"), StandardCharsets.UTF_8));
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
        assertEquals(List.of("Use 2.0.0"), refreshed.metadata().userCases());
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
        assertEquals(List.of("Use 1.0.0"), cached.metadata().userCases());
    }

    @Test
    void userCasesSurviveRestartAndRemainOptionalInOldCaches() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime prepared = manager.prepare(AGENT_ID);
        Path identityFile = prepared.agentRoot().resolve(".campusclaw/agent.json");
        assertEquals(
                "Use 1.0.0",
                new ObjectMapper()
                        .readTree(identityFile.toFile())
                        .path("userCases")
                        .get(0)
                        .asText());
        MateServiceClient restartedClient = mock(MateServiceClient.class);
        var restarted = new AgentRuntimeManager(
                new AgentRuntimeProperties(tempDir.resolve("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L)),
                restartedClient,
                new ObjectMapper());

        assertEquals(
                List.of("Use 1.0.0"),
                restarted.prepareCached(AGENT_ID).metadata().userCases());
        var identity = new ObjectMapper().readTree(identityFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) identity).remove("userCases");
        new ObjectMapper().writeValue(identityFile.toFile(), identity);
        assertEquals(List.of(), restarted.prepareCached(AGENT_ID).metadata().userCases());
        ((com.fasterxml.jackson.databind.node.ObjectNode) identity)
                .putArray("userCases")
                .add(1);
        new ObjectMapper().writeValue(identityFile.toFile(), identity);
        assertNull(restarted.prepareCached(AGENT_ID));
        verifyNoInteractions(restartedClient);
    }

    @Test
    void shouldPreserveToolPermissionsWhenPublishedCachedAndRestarted() throws Exception {
        BoundTool agentTool = tool("isolate_port", "ask", "Agent tool", "7");
        BoundTool unknownTool = tool("future_tool", "unexpected", "Future tool", "8");
        BoundTool skillTool = tool("rotate_secret", "ask", "Skill tool", "9");
        List<BoundTool> agentTools = List.of(agentTool, unknownTool);
        List<BoundTool> skillTools = List.of(skillTool);
        when(client.getAgentRuntime(AGENT_ID))
                .thenReturn(runtime(List.of(child("researcher", CHILD_ID)), "prompt-v1", "1.0.0", agentTools));
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill(skillContent(), skillTools));

        PreparedAgentRuntime published = manager.prepare(AGENT_ID);
        assertBindingTools(published, agentTools, skillTools);
        assertBindingTools(manager.prepare(AGENT_ID), agentTools, skillTools);
        MateServiceClient restartedClient = mock(MateServiceClient.class);
        var restarted = new AgentRuntimeManager(
                new AgentRuntimeProperties(tempDir.resolve("agent"), Duration.ofSeconds(1L), Duration.ofSeconds(2L)),
                restartedClient,
                new ObjectMapper());

        assertBindingTools(restarted.prepareCached(AGENT_ID), agentTools, skillTools);
        verify(client).getAgentRuntime(AGENT_ID);
        verify(client).querySkillInfo(SKILL_ID);
        verifyNoInteractions(restartedClient);
    }

    @Test
    void shouldRejectOldCacheWhenAgentToolBindingsAreMissing() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        Path settings = manager.prepare(AGENT_ID).agentRoot().resolve(".campusclaw/settings.json");
        removeJsonField(settings, "bindingTools");

        assertNull(manager.prepareCached(AGENT_ID));
        manager.prepare(AGENT_ID);
        verify(client, times(2)).getAgentRuntime(AGENT_ID);
    }

    @Test
    void shouldRejectOldCacheWhenSkillToolBindingsAreMissing() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        Path manifest = manager.prepare(AGENT_ID).agentRoot().resolve(".campusclaw/skills/calendar/skill.json");
        removeJsonField(manifest, "bindingTools");

        assertNull(manager.prepareCached(AGENT_ID));
        manager.prepare(AGENT_ID);
        verify(client, times(2)).querySkillInfo(SKILL_ID);
    }

    @Test
    void cachedReadWaitsForRefreshToPublishOneSnapshot() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        manager.prepare(AGENT_ID);
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        when(client.getAgentRuntime(AGENT_ID)).thenAnswer(ignored -> {
            refreshStarted.countDown();
            assertTrue(releaseRefresh.await(2L, TimeUnit.SECONDS));
            return runtime("2.0.0", "prompt-v2");
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var refresh = executor.submit(() -> manager.refresh(AGENT_ID));
            assertTrue(refreshStarted.await(2L, TimeUnit.SECONDS));
            var cached = executor.submit(() -> manager.prepareCached(AGENT_ID));
            assertThrows(TimeoutException.class, () -> cached.get(100L, TimeUnit.MILLISECONDS));
            releaseRefresh.countDown();
            assertEquals(
                    List.of("Use 2.0.0"),
                    cached.get(2L, TimeUnit.SECONDS).metadata().userCases());
            refresh.get(2L, TimeUnit.SECONDS);
        } finally {
            releaseRefresh.countDown();
            executor.shutdownNow();
        }
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                ".",
                "..",
                "agent-a",
                "../agent-a",
                "/agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/..",
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\child",
                "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\0",
                "skill-11111111111111111111111111111111"
            })
    void rejectsAgentIdThatCouldEscapeRoot(String invalid) {
        assertThrows(IllegalArgumentException.class, () -> manager.prepare(invalid));
        assertThrows(IllegalArgumentException.class, () -> manager.prepareCached(invalid));
        assertThrows(IllegalArgumentException.class, () -> manager.refresh(invalid));

        verifyNoInteractions(client);
        assertFalse(Files.exists(tempDir.resolve("agent")));
    }

    @Test
    void rejectsInvalidConfiguredAgentsRootBeforePathResolution() {
        Path invalidAgentsRoot = tempDir.resolve("cache").resolve("..").resolve("agent");
        var properties = new AgentRuntimeProperties(invalidAgentsRoot, Duration.ofSeconds(1L), Duration.ofSeconds(2L));
        var boundaryManager = new AgentRuntimeManager(properties, client, new ObjectMapper());

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> boundaryManager.prepareCached(AGENT_ID));

        assertEquals("Invalid agents root path", exception.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void rejectsCanonicalAgentPathOutsideConfiguredRoot() throws Exception {
        Path agentsRoot = tempDir.resolve("agent");
        Path outsideRoot = tempDir.resolve("outside");
        Files.createDirectories(agentsRoot);
        Files.createDirectories(outsideRoot);
        Files.createSymbolicLink(agentsRoot.resolve(AGENT_ID), outsideRoot);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> manager.prepareCached(AGENT_ID));

        assertEquals("Canonical Agent path escapes agents root", exception.getMessage());
    }

    @Test
    void rejectsCanonicalAgentPathAliasedToAnotherAgent() throws Exception {
        Path agentsRoot = tempDir.resolve("agent");
        Path otherAgentRoot = agentsRoot.resolve(CHILD_ID);
        Path otherManagedRoot = otherAgentRoot.resolve(".campusclaw");
        Files.createDirectories(otherManagedRoot);
        Path otherSystemFile = otherManagedRoot.resolve("SYSTEM.md");
        Files.writeString(otherSystemFile, "other-agent", StandardCharsets.UTF_8);
        Files.createSymbolicLink(agentsRoot.resolve(AGENT_ID), otherAgentRoot);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> manager.refresh(AGENT_ID));

        assertEquals("Canonical Agent path does not match requested Agent directory", exception.getMessage());
        assertEquals("other-agent", Files.readString(otherSystemFile, StandardCharsets.UTF_8));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsCanonicalAgentPathAliasedToAgentsRoot() throws Exception {
        Path agentsRoot = tempDir.resolve("agent");
        Files.createDirectories(agentsRoot);
        Files.createSymbolicLink(agentsRoot.resolve(AGENT_ID), agentsRoot);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> manager.prepareCached(AGENT_ID));

        assertEquals("Canonical Agent path does not match requested Agent directory", exception.getMessage());
    }

    @Test
    void nullSkillContentFailsPrepareWithoutPublishing() {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime("1.0.0", "prompt-v1"));
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill(null));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare(AGENT_ID));

        assertFalse(Files.exists(tempDir.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw")));
    }

    @Test
    void blankSkillContentFailsPrepare() {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime("1.0.0", "prompt-v1"));
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill("   "));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare(AGENT_ID));
    }

    @Test
    void frontmatterNameMismatchFailsPrepare() {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime("1.0.0", "prompt-v1"));
        when(client.querySkillInfo(SKILL_ID))
                .thenReturn(skill("---\nname: other-skill\ndescription: Calendar workflow\n---\nBody\n"));

        assertThrows(AgentRuntimeException.class, () -> manager.prepare(AGENT_ID));

        assertFalse(Files.exists(tempDir.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw")));
    }

    @Test
    void failedRefreshKeepsPreviouslyPublishedCache() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        Path skillFile = first.agentRoot().resolve(".campusclaw/skills/calendar/SKILL.md");
        assertEquals(skillContent(), Files.readString(skillFile, StandardCharsets.UTF_8));

        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill(null));
        assertThrows(AgentRuntimeException.class, () -> manager.refresh(AGENT_ID));

        assertEquals(skillContent(), Files.readString(skillFile, StandardCharsets.UTF_8));
    }

    @Test
    void corruptedFrontmatterNameIsRejectedAndRefetched() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        Path skillFile = first.agentRoot().resolve(".campusclaw/skills/calendar/SKILL.md");
        Files.writeString(
                skillFile, "---\nname: other-skill\ndescription: Calendar workflow\n---\n", StandardCharsets.UTF_8);

        PreparedAgentRuntime repaired = manager.prepare(AGENT_ID);

        assertEquals(skillContent(), Files.readString(skillFile, StandardCharsets.UTF_8));
        verify(client, times(2)).querySkillInfo(SKILL_ID);
    }

    @Test
    void oversizedCachedSkillFileTriggersRefetch() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        Path skillFile = first.agentRoot().resolve(".campusclaw/skills/calendar/SKILL.md");
        Files.writeString(skillFile, "x".repeat(1024 * 1024 + 1), StandardCharsets.UTF_8);

        PreparedAgentRuntime repaired = manager.prepare(AGENT_ID);

        assertEquals(skillContent(), Files.readString(skillFile, StandardCharsets.UTF_8));
        verify(client, times(2)).querySkillInfo(SKILL_ID);
    }

    @Test
    void overlongCachedDescriptionTriggersRefetch() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        Path skillFile = first.agentRoot().resolve(".campusclaw/skills/calendar/SKILL.md");

        // 描述超过 ClawConstants.Skill.MAX_DESCRIPTION_LENGTH(1024):缓存读取判不完整并重新拉取。
        Files.writeString(
                skillFile,
                "---\nname: calendar\ndescription: " + "d".repeat(2000) + "\n---\nBody\n",
                StandardCharsets.UTF_8);

        PreparedAgentRuntime repaired = manager.prepare(AGENT_ID);

        assertEquals(skillContent(), Files.readString(skillFile, StandardCharsets.UTF_8));
        verify(client, times(2)).querySkillInfo(SKILL_ID);
    }

    @Test
    void preparedSkillsLoadThroughRuntimeAgentPromptLoader() throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime prepared = manager.prepare(AGENT_ID);

        String prompt = new RuntimeAgentPromptLoader().load(prepared.agentRoot().resolve(".campusclaw"));

        assertTrue(prompt.contains("calendar"));
        assertTrue(prompt.contains("Calendar workflow"));
        assertTrue(prompt.contains("prompt-v1"));
    }

    private void stubRuntime(String version, String prompt) {
        when(client.getAgentRuntime(AGENT_ID)).thenReturn(runtime(version, prompt));
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skill(skillContent()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools"})
    void invalidSkillNameFailsPrepareWithoutPublishing(String name) {
        stubRuntime("1.0.0", "prompt-v1");
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skillWithName(name));

        AgentRuntimeException error = assertThrows(AgentRuntimeException.class, () -> manager.prepare(AGENT_ID));

        assertTrue(error.getCause() instanceof SkillLoadException);
        assertFalse(Files.exists(tempDir.resolve("agent").resolve(AGENT_ID).resolve(".campusclaw")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools"})
    void invalidSkillNameFailsRefreshAndPreservesPublishedCache(String name) throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        when(client.querySkillInfo(SKILL_ID)).thenReturn(skillWithName(name));

        AgentRuntimeException error = assertThrows(AgentRuntimeException.class, () -> manager.refresh(AGENT_ID));

        assertTrue(error.getCause() instanceof SkillLoadException);
        Path managed = first.agentRoot().resolve(".campusclaw");
        assertEquals(
                skillContent(), Files.readString(managed.resolve("skills/calendar/SKILL.md"), StandardCharsets.UTF_8));
        assertEquals("prompt-v1", manager.readSystemPrompt(manager.prepareCached(AGENT_ID)));
        assertFalse(Files.exists(managed.resolve("skills").resolve(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools"})
    void invalidCachedSkillNameIsRejectedAndRefetched(String name) throws Exception {
        stubRuntime("1.0.0", "prompt-v1");
        PreparedAgentRuntime first = manager.prepare(AGENT_ID);
        Path skills = first.agentRoot().resolve(".campusclaw/skills");
        Path invalidDirectory = Files.move(skills.resolve("calendar"), skills.resolve(name));
        Path manifest = invalidDirectory.resolve("skill.json");
        String metadata = Files.readString(manifest, StandardCharsets.UTF_8).replace("calendar", name);
        Files.writeString(manifest, metadata, StandardCharsets.UTF_8);
        Files.writeString(
                invalidDirectory.resolve("SKILL.md"), skillWithName(name).content(), StandardCharsets.UTF_8);

        assertNull(manager.prepareCached(AGENT_ID));
        verify(client).querySkillInfo(SKILL_ID);
        PreparedAgentRuntime repaired = manager.prepare(AGENT_ID);

        assertEquals(SKILL_ID, repaired.skillIdsByName().get("calendar"));
        assertFalse(Files.exists(invalidDirectory));
        assertEquals(skillContent(), Files.readString(skills.resolve("calendar/SKILL.md"), StandardCharsets.UTF_8));
        verify(client, times(2)).querySkillInfo(SKILL_ID);
    }

    private static SkillInfo skillWithName(String name) {
        return new SkillInfo(
                name,
                SKILL_ID,
                "1.0.0",
                "Invalid naming skill",
                "test",
                "---\nname: " + name + "\ndescription: Invalid workflow\n---\nBody.\n",
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static AgentRuntime runtime(String version, String prompt) {
        return runtime(List.of(child("researcher", CHILD_ID)), prompt, version);
    }

    private static AgentRuntime runtime(List<AgentReference> children, String prompt) {
        return runtime(children, prompt, "2.0.0");
    }

    private static AgentRuntime runtime(List<AgentReference> children, String prompt, String version) {
        return runtime(children, prompt, version, List.of());
    }

    private static AgentRuntime runtime(
            List<AgentReference> children, String prompt, String version, List<BoundTool> bindingTools) {
        return new AgentRuntime(
                List.of("gpt-4o"),
                List.of(new SkillReference(SKILL_ID, "1.0.0")),
                bindingTools,
                children,
                List.of("description"),
                "Agent A",
                true,
                AGENT_ID,
                "agent-a",
                prompt,
                List.of("Use " + version),
                version);
    }

    private static AgentReference child(String name, String id) {
        return new AgentReference(id, name, "Child", "Researches a task", "1.0.0");
    }

    private static SkillInfo skill(String content) {
        return skill(content, List.of());
    }

    private static SkillInfo skill(String content, List<BoundTool> bindingTools) {
        return new SkillInfo(
                "calendar",
                SKILL_ID,
                "1.0.0",
                "Calendar workflow",
                "booking",
                content,
                bindingTools,
                List.of(),
                List.of(new SkillFile("template-1", "request", "Template", "txt")),
                List.of(new SkillFile("reference-1", "guide", "Reference", "md")));
    }

    private static String skillContent() {
        return "---\nname: calendar\ndescription: Calendar workflow\n---\n\nUse the calendar workflow.\n";
    }

    private static BoundTool tool(String name, String permission, String displayName, String version) {
        return new BoundTool(
                "Tool description",
                displayName,
                "tool-22222222222222222222222222222222",
                "false",
                name,
                permission,
                "mate",
                version);
    }

    private static void assertBindingTools(
            PreparedAgentRuntime runtime, List<BoundTool> agentTools, List<BoundTool> skillTools) {
        assertEquals(agentTools, runtime.metadata().bindingTools());
        assertEquals(skillTools, runtime.skills().get(0).bindingTools());
    }

    private static void removeJsonField(Path file, String field) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(file.toFile());
        value.remove(field);
        objectMapper.writeValue(file.toFile(), value);
    }
}
