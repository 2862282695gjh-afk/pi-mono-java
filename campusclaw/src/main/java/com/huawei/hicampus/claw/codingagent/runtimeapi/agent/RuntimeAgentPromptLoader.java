/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.skill.Skill;
import com.huawei.hicampus.claw.codingagent.skill.SkillLoadException;
import com.huawei.hicampus.claw.codingagent.skill.SkillLoader;
import com.huawei.hicampus.claw.codingagent.skill.SkillPromptFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 从 Agent 当前只读目录安全装载 SYSTEM.md 和可见 Skill 摘要。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/19]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeAgentPromptLoader {
    private static final Logger log = LoggerFactory.getLogger(RuntimeAgentPromptLoader.class);

    private static final int MAX_SKILLS = 128;

    private static final int MAX_SCAN_DEPTH = 8;

    private final SkillLoader skillLoader = new SkillLoader();

    public String load(Path runtimeDirectory) {
        Path root = realDirectory(runtimeDirectory);
        String systemPrompt = readOptionalFile(root, root.resolve("SYSTEM.md"));
        List<Skill> skills = loadSkills(root, root.resolve("skills"));
        String skillsPrompt = SkillPromptFormatter.format(skills);
        if (systemPrompt.isBlank()) {
            return skillsPrompt;
        }
        return skillsPrompt.isBlank() ? systemPrompt : systemPrompt + "\n\n# Skills\n\n" + skillsPrompt;
    }

    public void validate(Path runtimeDirectory) {
        load(runtimeDirectory);
    }

    private List<Skill> loadSkills(Path root, Path skillsDirectory) {
        if (!Files.isDirectory(skillsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Path realSkills = safeRealPath(root, skillsDirectory);
        List<Path> files = new ArrayList<>();
        scanSkillFiles(root, realSkills, 0, files);
        files.sort(Comparator.comparing(Path::toString));
        return files.stream()
                .limit(MAX_SKILLS)
                .map(this::loadSkill)
                .filter(java.util.Objects::nonNull)
                .filter(skill -> !skill.disableModelInvocation())
                .toList();
    }

    private void scanSkillFiles(Path root, Path directory, int depth, List<Path> files) {
        if (depth > MAX_SCAN_DEPTH || files.size() >= MAX_SKILLS) {
            return;
        }
        Path skillFile = directory.resolve("SKILL.md");
        if (isSafeRegularFile(root, skillFile)) {
            files.add(safeRealPath(root, skillFile));
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (isScannableDirectory(root, entry)) {
                    scanSkillFiles(root, safeRealPath(root, entry), depth + 1, files);
                }
            }
        } catch (IOException error) {
            log.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.agent.skills.scan")
                    .addKeyValue("errorCode", RuntimeErrorCode.AGENT_NOT_AVAILABLE.name())
                    .addKeyValue("directory", directory)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.agent.skills.scan",
                            RuntimeErrorCode.AGENT_NOT_AVAILABLE.name());
            throw unavailable();
        }
    }

    private Skill loadSkill(Path skillFile) {
        requireManagedSize(skillFile);
        try {
            return skillLoader.loadFromFile(skillFile, "agent");
        } catch (SkillLoadException error) {
            log.warn("Ignoring invalid Runtime Agent skill {}: {}", skillFile, error.getMessage());
            return null;
        }
    }

    private static String readOptionalFile(Path root, Path candidate) {
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        if (!isSafeRegularFile(root, candidate)) {
            throw unavailable();
        }
        Path realFile = safeRealPath(root, candidate);
        requireManagedSize(realFile);
        try {
            return Files.readString(realFile, StandardCharsets.UTF_8);
        } catch (IOException error) {
            log.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.agent.prompt.read")
                    .addKeyValue("errorCode", RuntimeErrorCode.AGENT_NOT_AVAILABLE.name())
                    .addKeyValue("path", realFile)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.agent.prompt.read",
                            RuntimeErrorCode.AGENT_NOT_AVAILABLE.name());
            throw unavailable();
        }
    }

    private static boolean isScannableDirectory(Path root, Path path) {
        return !path.getFileName().toString().startsWith(".")
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && safeRealPath(root, path).startsWith(root);
    }

    private static boolean isSafeRegularFile(Path root, Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && safeRealPath(root, path).startsWith(root);
    }

    private static Path realDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable();
        }
        try {
            return path.toRealPath();
        } catch (IOException error) {
            log.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.agent.directory.resolve")
                    .addKeyValue("errorCode", RuntimeErrorCode.AGENT_NOT_AVAILABLE.name())
                    .addKeyValue("path", path)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.agent.directory.resolve",
                            RuntimeErrorCode.AGENT_NOT_AVAILABLE.name());
            throw unavailable();
        }
    }

    private static Path safeRealPath(Path root, Path path) {
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(root)) {
                throw unavailable();
            }
            return realPath;
        } catch (IOException error) {
            log.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.agent.path.resolve")
                    .addKeyValue("errorCode", RuntimeErrorCode.AGENT_NOT_AVAILABLE.name())
                    .addKeyValue("path", path)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.agent.path.resolve",
                            RuntimeErrorCode.AGENT_NOT_AVAILABLE.name());
            throw unavailable();
        }
    }

    private static void requireManagedSize(Path path) {
        try {
            if (Files.size(path) > Skill.MAX_FILE_BYTES) {
                throw unavailable();
            }
        } catch (IOException error) {
            log.atError()
                    .addKeyValue("event", "campusclaw.failure")
                    .addKeyValue("operation", "runtime.agent.file.inspect")
                    .addKeyValue("errorCode", RuntimeErrorCode.AGENT_NOT_AVAILABLE.name())
                    .addKeyValue("path", path)
                    .setCause(error)
                    .log(
                            "CampusClaw failure: operation={}, errorCode={}",
                            "runtime.agent.file.inspect",
                            RuntimeErrorCode.AGENT_NOT_AVAILABLE.name());
            throw unavailable();
        }
    }

    private static RuntimeApiException unavailable() {
        return new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
    }
}
