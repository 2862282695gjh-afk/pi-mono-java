/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.huawei.hicampus.claw.codingagent.runtimeapi.agent.AgentPromptSnapshotDTO.PromptSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;
import com.huawei.hicampus.claw.codingagent.skill.Skill;
import com.huawei.hicampus.claw.codingagent.skill.SkillLoadException;
import com.huawei.hicampus.claw.codingagent.skill.SkillLoader;
import com.huawei.hicampus.claw.codingagent.skill.SkillPromptFormatter;
import com.huawei.hicampus.claw.common.constant.ClawConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 从 Agent 当前只读目录安全装载基础提示词和模式正文快照。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class RuntimeAgentPromptLoader {
    private static final Logger log = LoggerFactory.getLogger(RuntimeAgentPromptLoader.class);

    private static final int MAX_SKILLS = 128;

    private static final int MAX_SCAN_DEPTH = 8;

    private static final String PROMPT_RESOURCE_DIRECTORY = "prompts/plan/";

    private final SkillLoader skillLoader = new SkillLoader();

    private final String builtinPlanPrompt;

    private final String builtinBuildPrompt;

    public RuntimeAgentPromptLoader() {
        builtinPlanPrompt = loadBuiltinPrompt(ClawConstants.Runtime.PLAN_PROMPT_FILE_NAME);
        builtinBuildPrompt = loadBuiltinPrompt(ClawConstants.Runtime.BUILD_PROMPT_FILE_NAME);
    }

    /**
     * 装载基础 SYSTEM 与可见 Skill 提示词。
     *
     * @param runtimeDirectory 受管运行目录
     * @return 基础提示词
     */
    public String load(Path runtimeDirectory) {
        return loadBasePrompt(realDirectory(runtimeDirectory));
    }

    /**
     * 从同一受管目录生成本次执行使用的不可变提示词快照。
     *
     * @param runtimeDirectory 受管运行目录
     * @return 基础提示词与逐模式选择结果
     */
    public AgentPromptSnapshotDTO loadSnapshot(Path runtimeDirectory) {
        Path root = realDirectory(runtimeDirectory);
        String basePrompt = loadBasePrompt(root);
        Map<String, String> overrides = loadOverridesFromRoot(root);
        String planPrompt = overrides.getOrDefault(ClawConstants.Runtime.PLAN_PROMPT_FILE_NAME, builtinPlanPrompt);
        String buildPrompt = overrides.getOrDefault(ClawConstants.Runtime.BUILD_PROMPT_FILE_NAME, builtinBuildPrompt);
        PromptSource planSource = source(overrides, ClawConstants.Runtime.PLAN_PROMPT_FILE_NAME);
        PromptSource buildSource = source(overrides, ClawConstants.Runtime.BUILD_PROMPT_FILE_NAME);
        return new AgentPromptSnapshotDTO(basePrompt, planPrompt, buildPrompt, planSource, buildSource);
    }

    /**
     * 读取需要在目录重建时保留的合法业务模式文件。
     *
     * @param runtimeDirectory 当前受管运行目录
     * @return 文件名到完整正文的不可变映射；缺失文件不包含在映射中
     */
    public Map<String, String> loadOverrides(Path runtimeDirectory) {
        return loadOverridesFromRoot(realDirectory(runtimeDirectory));
    }

    public void validate(Path runtimeDirectory) {
        loadSnapshot(runtimeDirectory);
    }

    private String loadBasePrompt(Path root) {
        String systemPrompt = readOptionalFile(root, root.resolve(ClawConstants.Runtime.SYSTEM_FILE_NAME));
        List<Skill> skills = loadSkills(root, root.resolve(ClawConstants.Skill.DIRECTORY_NAME));
        String skillsPrompt = SkillPromptFormatter.format(skills);
        if (systemPrompt.isBlank()) {
            return skillsPrompt;
        }
        return skillsPrompt.isBlank() ? systemPrompt : systemPrompt + "\n\n# Skills\n\n" + skillsPrompt;
    }

    private Map<String, String> loadOverridesFromRoot(Path root) {
        Map<String, String> overrides = new LinkedHashMap<>();
        readOptionalModeFile(root, ClawConstants.Runtime.PLAN_PROMPT_FILE_NAME)
                .ifPresent(prompt -> overrides.put(ClawConstants.Runtime.PLAN_PROMPT_FILE_NAME, prompt));
        readOptionalModeFile(root, ClawConstants.Runtime.BUILD_PROMPT_FILE_NAME)
                .ifPresent(prompt -> overrides.put(ClawConstants.Runtime.BUILD_PROMPT_FILE_NAME, prompt));
        return Map.copyOf(overrides);
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
        Path skillFile = directory.resolve(ClawConstants.Skill.MARKDOWN_FILE_NAME);
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
            logFailure("runtime.agent.skills.scan", directory, error);
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
        Optional<BasicFileAttributes> attributes = readAttributes(candidate);
        if (attributes.isEmpty()) {
            return "";
        }
        requireSafeRegularFile(root, candidate, attributes.orElseThrow());
        return readUtf8File(safeRealPath(root, candidate));
    }

    private static Optional<String> readOptionalModeFile(Path root, String fileName) {
        Path candidate = root.resolve(fileName);
        Optional<BasicFileAttributes> attributes = readAttributes(candidate);
        if (attributes.isEmpty()) {
            return Optional.empty();
        }
        requireSafeRegularFile(root, candidate, attributes.orElseThrow());
        String prompt = readUtf8File(safeRealPath(root, candidate));
        if (prompt.isBlank()) {
            throw unavailable();
        }
        return Optional.of(prompt);
    }

    private static Optional<BasicFileAttributes> readAttributes(Path path) {
        try {
            return Optional.of(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException error) {
            return Optional.empty();
        } catch (IOException error) {
            logFailure("runtime.agent.file.inspect", path, error);
            throw unavailable();
        }
    }

    private static void requireSafeRegularFile(Path root, Path path, BasicFileAttributes attributes) {
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw unavailable();
        }
        Path realFile = safeRealPath(root, path);
        requireManagedSize(realFile);
    }

    private static String readUtf8File(Path path) {
        try {
            byte[] content = Files.readAllBytes(path);
            if (content.length > ClawConstants.Skill.MAX_FILE_BYTES) {
                throw unavailable();
            }
            return decodeUtf8(content);
        } catch (IOException error) {
            logFailure("runtime.agent.prompt.read", path, error);
            throw unavailable();
        }
    }

    private static String decodeUtf8(byte[] content) throws java.nio.charset.CharacterCodingException {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(content)).toString();
    }

    private static String loadBuiltinPrompt(String fileName) {
        var resource = new ClassPathResource(PROMPT_RESOURCE_DIRECTORY + fileName);
        try {
            byte[] content = resource.getContentAsByteArray();
            String prompt = decodeUtf8(content);
            if (prompt.isBlank()) {
                throw new IllegalStateException("Built-in mode prompt must not be blank: " + fileName);
            }
            return prompt;
        } catch (IOException error) {
            throw new IllegalStateException("Built-in mode prompt is unavailable: " + fileName, error);
        }
    }

    private static PromptSource source(Map<String, String> overrides, String fileName) {
        return overrides.containsKey(fileName) ? PromptSource.AGENT_FILE : PromptSource.BUILTIN;
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
            logFailure("runtime.agent.directory.resolve", path, error);
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
            logFailure("runtime.agent.path.resolve", path, error);
            throw unavailable();
        }
    }

    private static void requireManagedSize(Path path) {
        try {
            if (Files.size(path) > ClawConstants.Skill.MAX_FILE_BYTES) {
                throw unavailable();
            }
        } catch (IOException error) {
            logFailure("runtime.agent.file.inspect", path, error);
            throw unavailable();
        }
    }

    private static void logFailure(String operation, Path path, IOException error) {
        log.atError()
                .addKeyValue("event", "campusclaw.failure")
                .addKeyValue("operation", operation)
                .addKeyValue("errorCode", RuntimeErrorCode.AGENT_NOT_AVAILABLE.name())
                .addKeyValue("path", path)
                .setCause(error)
                .log(
                        "CampusClaw failure: operation={}, errorCode={}",
                        operation,
                        RuntimeErrorCode.AGENT_NOT_AVAILABLE.name());
    }

    private static RuntimeApiException unavailable() {
        return new RuntimeApiException(RuntimeErrorCode.AGENT_NOT_AVAILABLE);
    }
}
