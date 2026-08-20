/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * 递归扫描目录，从 SKILL.md 文件发现并加载 {@link Skill}。
 * <p>
 * 发现规则：
 * <ul>
 *   <li>目录包含 SKILL.md 时，将其视为 Skill 根目录，不再继续递归</li>
 *   <li>否则递归扫描子目录并查找 SKILL.md</li>
 * </ul>
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    static final String SKILL_FILENAME = "SKILL.md";
    private static final Pattern NAME_REGEX = Pattern.compile(Skill.NAME_PATTERN);
    private static final String FRONTMATTER_DELIMITER = "---";

    /**
     * 递归扫描指定目录中的 SKILL.md 并加载全部 Skill。
     *
     * @param dir 扫描根目录
     * @param source 来源标识，例如 user 或 project
     * @return 已发现的 Skill 列表；无效文件会被跳过
     */
    public List<Skill> loadFromDirectory(Path dir, String source) {
        List<Skill> skills = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return skills;
        }
        scanDirectory(dir, source, skills);
        return skills;
    }

    /**
     * 从 SKILL.md 文件加载单个 Skill。
     *
     * @param filePath SKILL.md 文件路径
     * @param source 来源标识，例如 user 或 project
     * @return 已加载的 Skill
     * @throws SkillLoadException 文件无法读取、解析或校验失败时抛出
     */
    public Skill loadFromFile(Path filePath, String source) {
        return parseSkillFile(filePath, source);
    }

    private void scanDirectory(Path dir, String source, List<Skill> skills) {
        Path skillFile = dir.resolve(SKILL_FILENAME);
        if (Files.isRegularFile(skillFile)) {
            // 当前目录是 Skill 根目录，加载后不再向下递归。
            try {
                skills.add(loadSkillFile(skillFile, source));
            } catch (SkillLoadException e) {
                log.debug("Skipping invalid skill file during directory scan: {}", skillFile, e);
            }
            return;
        }

        // 当前目录没有 SKILL.md，继续递归扫描子目录。
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".")) {
                    scanDirectory(entry, source, skills);
                }
            }
        } catch (IOException e) {
            log.debug("Skipping unreadable skill directory: {}", dir, e);
        }
    }

    /**
     * 使用本地解析器加载单个 Skill 文件。
     *
     * @param skillFile Skill 文件
     * @param source 来源标识
     * @return 已加载的 Skill
     */
    private Skill loadSkillFile(Path skillFile, String source) {
        return parseSkillFile(skillFile, source);
    }

    Skill parseSkillFile(Path filePath, String source) {
        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillLoadException("Failed to read skill file: " + filePath, e);
        }

        Map<String, Object> frontmatter = parseFrontmatter(content);

        Path baseDir = filePath.getParent();
        String parentDirName = baseDir != null ? baseDir.getFileName().toString() : "";

        // 名称优先取 frontmatter，其次取父目录名。
        String name = frontmatter.containsKey("name") ? String.valueOf(frontmatter.get("name")) : parentDirName;

        validateName(name, filePath);

        // 描述为必填字段。
        String description =
                frontmatter.containsKey("description") ? String.valueOf(frontmatter.get("description")) : null;

        if (description == null || description.isBlank()) {
            throw new SkillLoadException("Skill description is required: " + filePath);
        }
        if (description.length() > Skill.MAX_DESCRIPTION_LENGTH) {
            throw new SkillLoadException(
                    "Skill description exceeds " + Skill.MAX_DESCRIPTION_LENGTH + " characters: " + filePath);
        }

        // 解析禁止模型调用标记。
        boolean disableModelInvocation = Boolean.TRUE.equals(frontmatter.get("disable-model-invocation"));

        return new Skill(name, description, filePath, baseDir, source, disableModelInvocation);
    }

    static void validateName(String name, Path filePath) {
        if (name == null || name.isEmpty()) {
            throw new SkillLoadException("Skill name is required: " + filePath);
        }
        if (name.length() > Skill.MAX_NAME_LENGTH) {
            throw new SkillLoadException("Skill name exceeds " + Skill.MAX_NAME_LENGTH + " characters: " + filePath);
        }
        if (!NAME_REGEX.matcher(name).matches()) {
            throw new SkillLoadException(
                    "Skill name contains invalid characters (must be lowercase a-z, 0-9, hyphens): " + filePath);
        }
    }

    /**
     * 解析由 {@code ---} 分隔的 YAML frontmatter；不存在时返回空 Map。
     *
     * @param content 文件内容
     *
     * @return frontmatter 字段 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseFrontmatter(String content) {
        if (content == null || !content.startsWith(FRONTMATTER_DELIMITER)) {
            return Map.of();
        }

        int firstDelimEnd = content.indexOf('\n');
        if (firstDelimEnd < 0) {
            return Map.of();
        }

        int secondDelimStart = content.indexOf("\n" + FRONTMATTER_DELIMITER, firstDelimEnd + 1);
        if (secondDelimStart < 0) {
            return Map.of();
        }

        String yamlBlock = content.substring(firstDelimEnd + 1, secondDelimStart);
        if (yamlBlock.isBlank()) {
            return Map.of();
        }

        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(yamlBlock);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 移除 YAML frontmatter，仅返回正文。
     *
     * @param content 文件内容
     * @return 正文内容
     */
    static String stripFrontmatter(String content) {
        if (content == null || !content.startsWith(FRONTMATTER_DELIMITER)) {
            return content != null ? content : "";
        }

        int firstDelimEnd = content.indexOf('\n');
        if (firstDelimEnd < 0) {
            return content;
        }

        int secondDelimStart = content.indexOf("\n" + FRONTMATTER_DELIMITER, firstDelimEnd + 1);
        if (secondDelimStart < 0) {
            return content;
        }

        // 查找结束分隔符所在行的末尾。
        int bodyStart = content.indexOf('\n', secondDelimStart + 1);
        if (bodyStart < 0) {
            return "";
        }

        return content.substring(bodyStart + 1);
    }
}
