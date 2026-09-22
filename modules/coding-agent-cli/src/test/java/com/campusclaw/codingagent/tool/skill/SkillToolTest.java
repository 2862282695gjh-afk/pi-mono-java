/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.campusclaw.agent.tool.AgentContext;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.agent.tool.ToolExecutionPipeline;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.ai.types.ToolCall;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SkillTool} 的可见性、正文剥离、资源抽样和工作区边界测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
class SkillToolTest {

    private static final String AGENT_ID = "agent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @TempDir
    Path agentRoot;

    @Test
    void shouldReturnBodyBaseDirectoryAndAtMostTenSampledResourcePaths() throws Exception {
        String content = "---\nname: course\ndescription: Course lookup\n---\n# Course\nUse query-course.";
        Path skillDirectory = createSkillDirectory("course", content);
        Files.writeString(skillDirectory.resolve("skill.json"), "private-id", StandardCharsets.UTF_8);
        Path references = Files.createDirectories(skillDirectory.resolve("references"));
        for (int index = 0; index < 12; index++) {
            Files.writeString(
                    references.resolve("tool-" + String.format(java.util.Locale.ROOT, "%02d", index) + ".json"),
                    "definition-" + index,
                    StandardCharsets.UTF_8);
        }
        Path outside =
                Files.writeString(agentRoot.getParent().resolve("outside.txt"), "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(references.resolve("outside-link"), outside);
        SkillTool tool = tool(skill("course", content));

        var result = tool.execute("call", Map.of("name", "course"), null, null);
        String output = ((TextContent) result.content().getFirst()).text();

        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.PARALLEL);
        assertThat(tool.parameters().path("properties").has("resource")).isFalse();
        assertThat(output)
                .contains("<skill_content name=\"course\">", "# Course", "Use query-course.")
                .contains("Base directory for this skill: " + skillDirectory.toRealPath())
                .contains("Note: file list is sampled.")
                .doesNotContain("description: Course lookup", "private-id", "definition-", "outside-link", "secret");
        assertThat(output.lines().filter(line -> line.startsWith("<file>")).count())
                .isEqualTo(10);
    }

    @Test
    void shouldSucceedWithEmptyResourcesAndRejectUnknownOrModelDisabledSkill() throws Exception {
        String visible = "---\nname: visible\ndescription: Visible\n---\nVisible body";
        String disabled = "---\nname: hidden\ndescription: Hidden\ndisable-model-invocation: true\n---\nHidden body";
        createSkillDirectory("visible", visible);
        createSkillDirectory("hidden", disabled);
        SkillTool tool = tool(skill("visible", visible), skill("hidden", disabled));

        String output = ((TextContent) tool.execute("call", Map.of("name", "visible"), null, null)
                        .content()
                        .getFirst())
                .text();

        assertThat(output).contains("<skill_files>\n</skill_files>");
        assertThatThrownBy(() -> tool.execute("call", Map.of("name", "hidden"), null, null))
                .isInstanceOf(SkillTool.SkillExecutionException.class);
        assertThatThrownBy(() -> tool.execute("call", Map.of("name", "missing"), null, null))
                .isInstanceOf(SkillTool.SkillExecutionException.class);
    }

    @Test
    void shouldReadManagedMarkdownAtInvocationInsteadOfPreparedBody() throws Exception {
        String prepared = "---\nname: course\ndescription: Course\n---\nPrepared body";
        Path directory = createSkillDirectory("course", prepared);
        SkillTool tool = tool(skill("course", prepared));
        Files.writeString(
                directory.resolve("SKILL.md"),
                "---\nname: course\ndescription: Course\n---\nCurrent file body",
                StandardCharsets.UTF_8);

        var result = tool.execute("call", Map.of("name", "course"), null, null);

        assertThat(((TextContent) result.content().getFirst()).text())
                .contains("Current file body")
                .doesNotContain("Prepared body", "description: Course");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "directory", "symlink", "invalid-utf8", "unreadable"})
    void shouldReturnPipelineErrorWhenMarkdownCannotBeRead(String state) throws Exception {
        String prepared = "---\nname: course\ndescription: Course\n---\nPrepared body";
        Path markdown = createSkillDirectory("course", prepared).resolve("SKILL.md");
        SkillTool tool = tool(skill("course", prepared));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(markdown);
        makeMarkdownUnavailable(markdown, state);
        Map<String, Object> arguments = Map.of("name", "course");
        try {
            var result = new ToolExecutionPipeline()
                    .execute(
                            tool,
                            new ToolCall("call", "Skill", arguments),
                            arguments,
                            new AgentContext(),
                            new CancellationToken(),
                            null);

            assertThat(result.isError()).isTrue();
            assertThat(((TextContent) result.content().getFirst()).text())
                    .doesNotContain("Prepared body", "outside-secret");
        } finally {
            if ("unreadable".equals(state)) {
                Files.setPosixFilePermissions(markdown, permissions);
            }
        }
    }

    private void makeMarkdownUnavailable(Path markdown, String state) throws Exception {
        switch (state) {
            case "missing" -> Files.delete(markdown);
            case "directory" -> {
                Files.delete(markdown);
                Files.createDirectory(markdown);
            }
            case "symlink" -> {
                Path outside = Files.writeString(
                        agentRoot.getParent().resolve("outside-markdown.txt"),
                        "outside-secret",
                        StandardCharsets.UTF_8);
                Files.delete(markdown);
                Files.createSymbolicLink(markdown, outside);
            }
            case "invalid-utf8" -> Files.write(markdown, new byte[] {(byte) 0xc3, (byte) 0x28});
            case "unreadable" -> Files.setPosixFilePermissions(markdown, Set.of());
            default -> throw new IllegalArgumentException("Unknown test state: " + state);
        }
    }

    private Path createSkillDirectory(String name, String content) throws Exception {
        Path directory =
                Files.createDirectories(agentRoot.resolve(".campusclaw/skills").resolve(name));
        Files.writeString(directory.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
        return directory;
    }

    private SkillTool tool(SkillInfo... skills) {
        PreparedAgentRuntime runtime = mock(PreparedAgentRuntime.class);
        when(runtime.skills()).thenReturn(List.of(skills));
        return new SkillTool(new WorkspacePathResolver(), AgentWorkspaceBoundary.create(AGENT_ID, agentRoot), runtime);
    }

    private static SkillInfo skill(String name, String content) {
        return new SkillInfo(
                name,
                "skill-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "1.0.0",
                "Description",
                null,
                content,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
