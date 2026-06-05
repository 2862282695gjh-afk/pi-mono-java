/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.sandbox.DockerSandboxClient;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.sandbox.ResourceLimits;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.sandbox.SandboxResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxSkillParserTest {

    @TempDir
    Path tempDir;

    private DockerSandboxClient sandboxClient;
    private SandboxSkillParser parser;

    @BeforeEach
    void setUp() {
        sandboxClient = mock(DockerSandboxClient.class);
        parser = new SandboxSkillParser(sandboxClient);
    }

    @Nested
    class Availability {

        @Test
        void availableWhenClientAvailable() {
            when(sandboxClient.isAvailable()).thenReturn(true);

            assertThat(parser.isAvailable()).isTrue();
        }

        @Test
        void unavailableWhenClientUnavailable() {
            when(sandboxClient.isAvailable()).thenReturn(false);

            assertThat(parser.isAvailable()).isFalse();
        }
    }

    @Nested
    class ParseInSandbox {

        @Test
        void parsesSandboxJsonIntoSkill() throws Exception {
            Path skillFile = writeSkill("skill-a", "---\ndescription: host file\n---\nBody");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.success(
                            "{\"name\":\"skill-a\",\"description\":\"Parsed in sandbox\",\"disableModelInvocation\":true}"));

            Skill skill = parser.parseInSandbox(skillFile, "project");

            assertThat(skill.name()).isEqualTo("skill-a");
            assertThat(skill.description()).isEqualTo("Parsed in sandbox");
            assertThat(skill.filePath()).isEqualTo(skillFile);
            assertThat(skill.baseDir()).isEqualTo(skillFile.getParent());
            assertThat(skill.source()).isEqualTo("project");
            assertThat(skill.disableModelInvocation()).isTrue();
        }

        @Test
        void defaultsNameToParentDirectoryWhenSandboxOmitsName() throws Exception {
            Path skillFile = writeSkill("fallback-name", "body");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.success(
                            "{\"description\":\"Parsed in sandbox\",\"disableModelInvocation\":false}"));

            Skill skill = parser.parseInSandbox(skillFile, "user");

            assertThat(skill.name()).isEqualTo("fallback-name");
            assertThat(skill.disableModelInvocation()).isFalse();
        }

        @Test
        void throwsWhenSandboxUnavailable() throws Exception {
            Path skillFile = writeSkill("skill-a", "body");
            when(sandboxClient.isAvailable()).thenReturn(false);

            assertThatThrownBy(() -> parser.parseInSandbox(skillFile, "project"))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("Sandbox not available");
        }

        @Test
        void throwsWhenSandboxTimesOut() throws Exception {
            Path skillFile = writeSkill("skill-a", "body");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class))).thenReturn(SandboxResult.timeout(5));

            assertThatThrownBy(() -> parser.parseInSandbox(skillFile, "project"))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("timed out");
        }

        @Test
        void throwsWhenSandboxExitsNonZero() throws Exception {
            Path skillFile = writeSkill("skill-a", "body");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.builder().exitCode(2).stderr("bad yaml").stdout("").build());

            assertThatThrownBy(() -> parser.parseInSandbox(skillFile, "project"))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("bad yaml");
        }

        @Test
        void wrapsInvalidSandboxJson() throws Exception {
            Path skillFile = writeSkill("skill-a", "body");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.success("{\"name\":\"Bad Name\"}"));

            assertThatThrownBy(() -> parser.parseInSandbox(skillFile, "project"))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("Failed to parse sandbox result");
        }

        @Test
        void wrapsReadFailure() {
            when(sandboxClient.isAvailable()).thenReturn(true);

            assertThatThrownBy(() -> parser.parseInSandbox(tempDir.resolve("missing").resolve("SKILL.md"), "project"))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("Failed to read skill file");
        }
    }

    @Nested
    class LoadBodyInSandbox {

        @Test
        void returnsSandboxStdout() throws Exception {
            Path skillFile = writeSkill("skill-a", "---\ndescription: d\n---\nBody");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.success("Body\n"));

            assertThat(parser.loadBodyInSandbox(skillFile)).isEqualTo("Body\n");
        }

        @Test
        void failsWhenSandboxUnavailable() throws Exception {
            Path skillFile = writeSkill("skill-a", "Body");
            when(sandboxClient.isAvailable()).thenReturn(false);

            assertThatThrownBy(() -> parser.loadBodyInSandbox(skillFile))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("Sandbox not available");
        }

        @Test
        void failsOnTimeoutAndNonZeroExit() throws Exception {
            Path skillFile = writeSkill("skill-a", "Body");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.timeout(1))
                    .thenReturn(SandboxResult.builder().exitCode(3).stderr("cannot read").stdout("").build());

            assertThatThrownBy(() -> parser.loadBodyInSandbox(skillFile))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("timed out");
            assertThatThrownBy(() -> parser.loadBodyInSandbox(skillFile))
                    .isInstanceOf(SkillLoadException.class)
                    .hasMessageContaining("cannot read");
        }
    }

    @Nested
    class ValidateSkillInSandbox {

        @Test
        void returnsEmptyStringWhenValidationPasses() throws Exception {
            Path skillFile = writeSkill("skill-a", "Body");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.success("VALIDATION_PASSED\n"));

            assertThat(parser.validateSkillInSandbox(skillFile)).isEmpty();
        }

        @Test
        void returnsErrorOutputAndTimeoutMessages() throws Exception {
            Path skillFile = writeSkill("skill-a", "Body");
            when(sandboxClient.isAvailable()).thenReturn(true);
            when(sandboxClient.execute(anyCommand(), any(ResourceLimits.class)))
                    .thenReturn(SandboxResult.success("ERROR: dangerous path\n"))
                    .thenReturn(SandboxResult.timeout(1));

            assertThat(parser.validateSkillInSandbox(skillFile)).isEqualTo("ERROR: dangerous path");
            assertThat(parser.validateSkillInSandbox(skillFile)).isEqualTo("Validation timed out");
        }

        @Test
        void returnsUnavailableAndReadFailureMessages() {
            when(sandboxClient.isAvailable()).thenReturn(false);
            assertThat(parser.validateSkillInSandbox(tempDir.resolve("missing.md"))).isEqualTo("Sandbox not available");

            when(sandboxClient.isAvailable()).thenReturn(true);
            assertThat(parser.validateSkillInSandbox(tempDir.resolve("missing.md")))
                    .contains("Failed to read skill file");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> anyCommand() {
        return any(List.class);
    }

    private Path writeSkill(String dirName, String content) throws Exception {
        Path dir = tempDir.resolve(dirName);
        java.nio.file.Files.createDirectories(dir);
        Path file = dir.resolve("SKILL.md");
        java.nio.file.Files.writeString(file, content);
        return file;
    }
}
