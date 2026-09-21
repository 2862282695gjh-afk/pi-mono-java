/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO.PromptSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runtime Agent 基础提示词、模式覆盖和 Skill 安全装载测试。
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
    void fallsBackToBothBuiltinModePromptsWhenAgentFilesAreMissing() throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));

        AgentPromptSnapshotDTO snapshot = new RuntimeAgentPromptLoader().loadSnapshot(managed);

        assertThat(snapshot.planSource()).isEqualTo(PromptSource.BUILTIN);
        assertThat(snapshot.buildSource()).isEqualTo(PromptSource.BUILTIN);
        assertThat(snapshot.planPrompt())
                .startsWith("# Plan 模式")
                .contains("## Main Agent", "## Subagent", "<proposed_plan>", "</proposed_plan>", "ExitPlanMode({})");
        assertThat(snapshot.buildPrompt()).startsWith("# Build 模式").contains("获批方案", "<proposed_plan>", "空参数不是空方案");
    }

    @Test
    void selectsEachAgentModeFileIndependentlyWithoutMergingBuiltinText() throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        Files.writeString(managed.resolve("PLAN.md"), "# Custom Plan\nOnly plan.", StandardCharsets.UTF_8);

        AgentPromptSnapshotDTO planOnly = new RuntimeAgentPromptLoader().loadSnapshot(managed);
        Files.writeString(managed.resolve("BUILD.md"), "# Custom Build\nOnly build.", StandardCharsets.UTF_8);
        AgentPromptSnapshotDTO both = new RuntimeAgentPromptLoader().loadSnapshot(managed);

        assertThat(planOnly.planPrompt()).isEqualTo("# Custom Plan\nOnly plan.");
        assertThat(planOnly.planSource()).isEqualTo(PromptSource.AGENT_FILE);
        assertThat(planOnly.buildSource()).isEqualTo(PromptSource.BUILTIN);
        assertThat(both.planPrompt()).isEqualTo("# Custom Plan\nOnly plan.");
        assertThat(both.buildPrompt()).isEqualTo("# Custom Build\nOnly build.");
        assertThat(both.buildSource()).isEqualTo(PromptSource.AGENT_FILE);
        assertThat(both.planPrompt()).doesNotContain("你当前处于计划模式");
        assertThat(both.buildPrompt()).doesNotContain("你当前处于 build 模式");
    }

    @Test
    void keepsTwoAgentDirectoriesAndTheirModeOverridesIsolated() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("agent-a/.campusclaw"));
        Path second = Files.createDirectories(temporaryDirectory.resolve("agent-b/.campusclaw"));
        Files.writeString(first.resolve("PLAN.md"), "# Agent A Plan", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("PLAN.md"), "# Agent B Plan", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("BUILD.md"), "# Agent B Build", StandardCharsets.UTF_8);
        RuntimeAgentPromptLoader loader = new RuntimeAgentPromptLoader();

        AgentPromptSnapshotDTO firstSnapshot = loader.loadSnapshot(first);
        AgentPromptSnapshotDTO secondSnapshot = loader.loadSnapshot(second);

        assertThat(firstSnapshot.planPrompt()).isEqualTo("# Agent A Plan");
        assertThat(firstSnapshot.buildSource()).isEqualTo(PromptSource.BUILTIN);
        assertThat(secondSnapshot.planPrompt()).isEqualTo("# Agent B Plan");
        assertThat(secondSnapshot.buildPrompt()).isEqualTo("# Agent B Build");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAN.md", "BUILD.md"})
    void rejectsBlankModePromptInsteadOfFallingBack(String fileName) throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        Files.writeString(managed.resolve(fileName), " \n\t", StandardCharsets.UTF_8);

        assertUnavailable(() -> new RuntimeAgentPromptLoader().loadSnapshot(managed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAN.md", "BUILD.md"})
    void rejectsDirectoryAtModePromptPath(String fileName) throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        Files.createDirectory(managed.resolve(fileName));

        assertUnavailable(() -> new RuntimeAgentPromptLoader().loadSnapshot(managed));
    }

    @Test
    void rejectsModePromptSymlinkEvenWhenTargetStaysInsideAgentDirectory() throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        Path target = Files.writeString(managed.resolve("plan-target.md"), "target", StandardCharsets.UTF_8);
        Files.createSymbolicLink(managed.resolve("PLAN.md"), target);

        assertUnavailable(() -> new RuntimeAgentPromptLoader().loadSnapshot(managed));
    }

    @Test
    void rejectsMalformedUtf8ModePrompt() throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        Files.write(managed.resolve("PLAN.md"), new byte[] {(byte) 0xC3, (byte) 0x28});

        assertUnavailable(() -> new RuntimeAgentPromptLoader().loadSnapshot(managed));
    }

    @Test
    void rejectsOversizedModePrompt() throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        byte[] content = new byte[(int) ClawConstants.Skill.MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(content, (byte) 'x');
        Files.write(managed.resolve("BUILD.md"), content);

        assertUnavailable(() -> new RuntimeAgentPromptLoader().loadSnapshot(managed));
    }

    @Test
    void rejectsUnreadableModePromptWhenFilePermissionsCanEnforceIt() throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        Path plan = Files.writeString(managed.resolve("PLAN.md"), "custom", StandardCharsets.UTF_8);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(plan);
        Files.setPosixFilePermissions(plan, Set.of());
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(plan));
            assertUnavailable(() -> new RuntimeAgentPromptLoader().loadSnapshot(managed));
        } finally {
            Files.setPosixFilePermissions(plan, original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools"})
    void excludesInvalidSkillNamesFromPrompt(String name) throws Exception {
        Path managed = Files.createDirectory(temporaryDirectory.resolve(".campusclaw"));
        writeSkill(managed.resolve("skills/good-name/SKILL.md"), "good-name", false);
        writeSkill(managed.resolve("skills").resolve(name).resolve("SKILL.md"), name, false);

        String prompt = new RuntimeAgentPromptLoader().load(managed);

        assertThat(prompt).contains("<name>good-name</name>");
        assertThat(prompt).doesNotContain(name);
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

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(RuntimeApiException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(RuntimeErrorCode.AGENT_NOT_AVAILABLE));
    }
}
