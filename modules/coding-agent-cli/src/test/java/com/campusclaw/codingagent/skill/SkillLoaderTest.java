/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkillLoaderTest {

    @TempDir
    Path tempDir;

    SkillLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SkillLoader();
    }

    // -------------------------------------------------------------------
    // 解析 frontmatter
    // -------------------------------------------------------------------

    @Nested
    class FrontmatterParsing {

        @Test
        void parsesValidFrontmatter() {
            String content =
                    """
                    ---
                    name: my-skill
                    description: A test skill
                    disable-model-invocation: true
                    ---
                    Body content here.
                    """;
            var fm = SkillLoader.parseFrontmatter(content);

            assertEquals("my-skill", fm.get("name"));
            assertEquals("A test skill", fm.get("description"));
            assertEquals(true, fm.get("disable-model-invocation"));
        }

        @Test
        void returnsEmptyMapWhenNoFrontmatter() {
            var fm = SkillLoader.parseFrontmatter("Just body content.");
            assertTrue(fm.isEmpty());
        }

        @Test
        void returnsEmptyMapForNullContent() {
            var fm = SkillLoader.parseFrontmatter(null);
            assertTrue(fm.isEmpty());
        }

        @Test
        void returnsEmptyMapWhenOnlyOpeningDelimiter() {
            var fm = SkillLoader.parseFrontmatter("---\nname: test");
            assertTrue(fm.isEmpty());
        }

        @Test
        void returnsEmptyMapForEmptyFrontmatter() {
            var fm = SkillLoader.parseFrontmatter("---\n---\nBody");
            assertTrue(fm.isEmpty());
        }

        @Test
        void returnsEmptyMapForInvalidYaml() {
            String content = "---\n[invalid yaml: :\n---\nBody";
            var fm = SkillLoader.parseFrontmatter(content);
            assertTrue(fm.isEmpty());
        }
    }

    // -------------------------------------------------------------------
    // 移除 frontmatter
    // -------------------------------------------------------------------

    @Nested
    class StripFrontmatter {

        @Test
        void stripsValidFrontmatter() {
            String content = "---\nname: test\n---\nBody content.";
            assertEquals("Body content.", SkillLoader.stripFrontmatter(content));
        }

        @Test
        void returnsFullContentWhenNoFrontmatter() {
            assertEquals("Just body.", SkillLoader.stripFrontmatter("Just body."));
        }

        @Test
        void returnsEmptyStringForNull() {
            assertEquals("", SkillLoader.stripFrontmatter(null));
        }

        @Test
        void returnsEmptyWhenOnlyFrontmatter() {
            assertEquals("", SkillLoader.stripFrontmatter("---\nname: test\n---"));
        }
    }

    // -------------------------------------------------------------------
    // 名称校验
    // -------------------------------------------------------------------

    @Nested
    class NameValidation {

        @Test
        void acceptsValidName() {
            assertDoesNotThrow(() -> SkillLoader.validateName("my-skill-123", Path.of("/test")));
        }

        @Test
        void rejectsUppercaseLetters() {
            assertThrows(SkillLoadException.class, () -> SkillLoader.validateName("MySkill", Path.of("/test")));
        }

        @Test
        void rejectsUnderscores() {
            assertThrows(SkillLoadException.class, () -> SkillLoader.validateName("my_skill", Path.of("/test")));
        }

        @Test
        void rejectsSpaces() {
            assertThrows(SkillLoadException.class, () -> SkillLoader.validateName("my skill", Path.of("/test")));
        }

        @Test
        void rejectsEmptyName() {
            assertThrows(SkillLoadException.class, () -> SkillLoader.validateName("", Path.of("/test")));
        }

        @Test
        void rejectsNullName() {
            assertThrows(SkillLoadException.class, () -> SkillLoader.validateName(null, Path.of("/test")));
        }

        @Test
        void rejectsNameExceeding64Characters() {
            String longName = "a".repeat(65);
            assertThrows(SkillLoadException.class, () -> SkillLoader.validateName(longName, Path.of("/test")));
        }

        @Test
        void acceptsNameExactly64Characters() {
            String name = "a".repeat(64);
            assertDoesNotThrow(() -> SkillLoader.validateName(name, Path.of("/test")));
        }
    }

    // -------------------------------------------------------------------
    // 从文件加载
    // -------------------------------------------------------------------

    @Nested
    class LoadFromFile {

        @Test
        void loadsSkillWithFrontmatter() throws IOException {
            Path skillDir = tempDir.resolve("my-skill");
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(
                    skillFile,
                    """
                    ---
                    name: my-skill
                    description: A test skill
                    ---
                    Skill body content.
                    """);

            Skill skill = loader.loadFromFile(skillFile, "project");

            assertEquals("my-skill", skill.name());
            assertEquals("A test skill", skill.description());
            assertEquals(skillFile, skill.filePath());
            assertEquals(skillDir, skill.baseDir());
            assertEquals("project", skill.source());
            assertFalse(skill.disableModelInvocation());
        }

        @Test
        void shouldRejectMissingNameWithoutDirectoryFallback() throws IOException {
            Path skillDir = tempDir.resolve("commit");
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(
                    skillFile,
                    """
                    ---
                    description: Create commits
                    ---
                    Body.
                    """);

            assertThrows(SkillLoadException.class, () -> loader.loadFromFile(skillFile, "user"));
        }

        @Test
        void respectsDisableModelInvocation() throws IOException {
            Path skillDir = tempDir.resolve("hidden-skill");
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(
                    skillFile,
                    """
                    ---
                    name: hidden-skill
                    description: A hidden skill
                    disable-model-invocation: true
                    ---
                    Body.
                    """);

            Skill skill = loader.loadFromFile(skillFile, "user");

            assertTrue(skill.disableModelInvocation());
        }

        @Test
        void throwsWhenDescriptionMissing() throws IOException {
            Path skillDir = tempDir.resolve("no-desc");
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(
                    skillFile,
                    """
                    ---
                    name: no-desc
                    ---
                    Body without description.
                    """);

            assertThrows(SkillLoadException.class, () -> loader.loadFromFile(skillFile, "project"));
        }

        @Test
        void throwsWhenDescriptionExceedsMaxLength() throws IOException {
            Path skillDir = tempDir.resolve("long-desc");
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(skillFile, "---\nname: long-desc\ndescription: " + "x".repeat(1025) + "\n---\nBody.");

            assertThrows(SkillLoadException.class, () -> loader.loadFromFile(skillFile, "project"));
        }

        @Test
        void throwsWhenNameInvalid() throws IOException {
            Path skillDir = tempDir.resolve("INVALID");
            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            Files.writeString(
                    skillFile,
                    """
                    ---
                    name: INVALID
                    description: Has invalid name
                    ---
                    Body.
                    """);

            assertThrows(SkillLoadException.class, () -> loader.loadFromFile(skillFile, "project"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"-pdf", "pdf-", "pdf--tools", "-"})
        void rejectsInvalidHyphensInFrontmatter(String name) throws IOException {
            Path skillFile = tempDir.resolve("SKILL.md");
            Files.writeString(
                    skillFile,
                    "---\nname: '" + name + "'\ndescription: Invalid skill\n---\nBody.",
                    StandardCharsets.UTF_8);

            SkillLoadException error =
                    assertThrows(SkillLoadException.class, () -> loader.loadFromFile(skillFile, "managed"));

            assertTrue(error.getMessage().contains("single separating hyphens"));
        }

        @Test
        void throwsForNonexistentFile() {
            assertThrows(
                    SkillLoadException.class,
                    () -> loader.loadFromFile(tempDir.resolve("nonexistent/SKILL.md"), "project"));
        }
    }

    // -------------------------------------------------------------------
    // 从目录加载
    // -------------------------------------------------------------------

    @Nested
    class LoadFromDirectory {

        @Test
        void findsSkillInDirectSubdirectory() throws IOException {
            Path skillDir = tempDir.resolve("my-skill");
            Files.createDirectories(skillDir);
            Files.writeString(
                    skillDir.resolve("SKILL.md"),
                    """
                    ---
                    name: my-skill
                    description: Test skill
                    ---
                    Body.
                    """);

            List<Skill> skills = loader.loadFromDirectory(tempDir, "project");

            assertEquals(1, skills.size());
            assertEquals("my-skill", skills.get(0).name());
        }

        @Test
        void findsMultipleSkills() throws IOException {
            for (String name : List.of("skill-a", "skill-b", "skill-c")) {
                Path dir = tempDir.resolve(name);
                Files.createDirectories(dir);
                Files.writeString(
                        dir.resolve("SKILL.md"),
                        """
                        ---
                        name: %s
                        description: Skill %s
                        ---
                        Body.
                        """
                                .formatted(name, name));
            }

            List<Skill> skills = loader.loadFromDirectory(tempDir, "user");

            assertEquals(3, skills.size());
        }

        @Test
        void findsSkillInNestedDirectory() throws IOException {
            Path nested = tempDir.resolve("group").resolve("my-skill");
            Files.createDirectories(nested);
            Files.writeString(
                    nested.resolve("SKILL.md"),
                    """
                    ---
                    name: my-skill
                    description: Nested skill
                    ---
                    Body.
                    """);

            List<Skill> skills = loader.loadFromDirectory(tempDir, "project");

            assertEquals(1, skills.size());
            assertEquals("my-skill", skills.get(0).name());
        }

        @Test
        void doesNotRecurseIntoSkillRoot() throws IOException {
            // my-skill 包含 SKILL.md，属于 Skill 根目录。
            Path skillDir = tempDir.resolve("my-skill");
            Files.createDirectories(skillDir);
            Files.writeString(
                    skillDir.resolve("SKILL.md"),
                    """
                    ---
                    name: my-skill
                    description: Root skill
                    ---
                    Body.
                    """);

            // 忽略 Skill 根目录中的嵌套子目录。
            Path childDir = skillDir.resolve("child-skill");
            Files.createDirectories(childDir);
            Files.writeString(
                    childDir.resolve("SKILL.md"),
                    """
                    ---
                    name: child-skill
                    description: Should be ignored
                    ---
                    Body.
                    """);

            List<Skill> skills = loader.loadFromDirectory(tempDir, "project");

            assertEquals(1, skills.size());
            assertEquals("my-skill", skills.get(0).name());
        }

        @Test
        void returnsEmptyForNonexistentDirectory() {
            List<Skill> skills = loader.loadFromDirectory(tempDir.resolve("nonexistent"), "project");
            assertTrue(skills.isEmpty());
        }

        @Test
        void returnsEmptyForNullDirectory() {
            List<Skill> skills = loader.loadFromDirectory(null, "project");
            assertTrue(skills.isEmpty());
        }

        @Test
        void skipsInvalidSkillFiles() throws IOException {
            // 合法 Skill
            Path validDir = tempDir.resolve("valid-skill");
            Files.createDirectories(validDir);
            Files.writeString(
                    validDir.resolve("SKILL.md"),
                    """
                    ---
                    name: valid-skill
                    description: A valid skill
                    ---
                    Body.
                    """);

            // 非法 Skill（缺少描述）
            Path invalidDir = tempDir.resolve("invalid-skill");
            Files.createDirectories(invalidDir);
            Files.writeString(
                    invalidDir.resolve("SKILL.md"),
                    """
                    ---
                    name: invalid-skill
                    ---
                    No description.
                    """);

            List<Skill> skills = loader.loadFromDirectory(tempDir, "project");

            assertEquals(1, skills.size());
            assertEquals("valid-skill", skills.get(0).name());
        }

        @Test
        void skipsDotDirectories() throws IOException {
            Path dotDir = tempDir.resolve(".hidden");
            Files.createDirectories(dotDir);
            Files.writeString(
                    dotDir.resolve("SKILL.md"),
                    """
                    ---
                    name: hidden
                    description: Hidden skill
                    ---
                    Body.
                    """);

            List<Skill> skills = loader.loadFromDirectory(tempDir, "project");
            assertTrue(skills.isEmpty());
        }
    }
}
