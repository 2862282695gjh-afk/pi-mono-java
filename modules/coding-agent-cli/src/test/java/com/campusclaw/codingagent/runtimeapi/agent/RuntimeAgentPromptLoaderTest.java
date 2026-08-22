/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.campusclaw.codingagent.runtimeapi.error.RuntimeApiException;
import com.campusclaw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runtime Agent 系统提示词和 Skill 安全装载测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
class RuntimeAgentPromptLoaderTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void combinesSystemPromptAndVisibleSkillsInStableOrder() throws Exception {
        Path managed = Files.createDirectories(temporaryDirectory.resolve(".campusclaw"));
        Files.writeString(managed.resolve("SYSTEM.md"), "You are the campus assistant.", StandardCharsets.UTF_8);
        writeSkill(managed.resolve("skills/b-skill/SKILL.md"), "b-skill", false);
        writeSkill(managed.resolve("skills/a-skill/SKILL.md"), "a-skill", false);
        writeSkill(managed.resolve("skills/hidden/SKILL.md"), "hidden", true);

        String prompt = new RuntimeAgentPromptLoader().load(managed);

        assertThat(prompt).startsWith("You are the campus assistant.");
        assertThat(prompt).contains("<name>a-skill</name>", "<name>b-skill</name>");
        assertThat(prompt.indexOf("a-skill")).isLessThan(prompt.indexOf("b-skill"));
        assertThat(prompt).doesNotContain("<name>hidden</name>");
    }

    @Test
    void rejectsSystemPromptSymlinkThatEscapesAgentDirectory() throws Exception {
        Path agent = Files.createDirectory(temporaryDirectory.resolve("agent"));
        Path managed = Files.createDirectories(agent.resolve(".campusclaw"));
        Path outside =
                Files.writeString(temporaryDirectory.resolve("outside-system.md"), "outside", StandardCharsets.UTF_8);
        Files.createSymbolicLink(managed.resolve("SYSTEM.md"), outside);

        assertThatThrownBy(() -> new RuntimeAgentPromptLoader().load(managed))
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE));
    }

    @Test
    void ignoresLegacyCampusAgentPromptDirectory() throws Exception {
        Path agent = Files.createDirectories(temporaryDirectory.resolve("agent"));
        Path runtimeDirectory = Files.createDirectories(agent.resolve(".campusclaw"));
        Path legacyDirectory = Files.createDirectories(agent.resolve(".campusagent"));
        Files.writeString(
                legacyDirectory.resolve("SYSTEM.md"), "Legacy prompt must not be loaded.", StandardCharsets.UTF_8);

        String prompt = new RuntimeAgentPromptLoader().load(runtimeDirectory);

        assertThat(prompt).isEmpty();
    }

    private static void writeSkill(Path file, String name, boolean hidden) throws Exception {
        Files.createDirectories(file.getParent());
        String content = "---\nname: " + name + "\ndescription: Test skill\ndisable-model-invocation: " + hidden
                + "\n---\n# Instructions\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
