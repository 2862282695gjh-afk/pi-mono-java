/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 展开用户输入中的 {@code /skill:name-here [args]} 命令，读取对应的 SKILL.md 文件并封装为 XML 格式。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class SkillExpander {

    /**
     * 匹配输入开头的 {@code /skill:name}，其后可带空白字符和参数。分组 1 为 Skill 名称，分组 2 为可选参数。
     */
    private static final Pattern SKILL_COMMAND = Pattern.compile("^/skill:([a-z0-9-]+)(?:\\s+(.*))?$", Pattern.DOTALL);

    /**
     * 当用户输入匹配 {@code /skill:name [args]} 时，按以下步骤展开：
     * <ol>
     *   <li>按名称从注册表查找 Skill</li>
     *   <li>读取 SKILL.md 文件并移除 YAML frontmatter</li>
     *   <li>使用 XML {@code <skill>} 元素封装正文</li>
     *   <li>追加剩余参数</li>
     * </ol>
     * 输入不匹配或未找到 Skill 时原样返回。
     *
     * @param userInput 原始用户输入
     * @param registry Skill 注册表
     * @return 展开后的 Skill 内容或原始输入
     */
    public String expand(String userInput, SkillRegistry registry) {
        if (userInput == null || userInput.isEmpty()) {
            return userInput != null ? userInput : "";
        }

        Matcher matcher = SKILL_COMMAND.matcher(userInput.trim());
        if (!matcher.matches()) {
            return userInput;
        }

        String skillName = matcher.group(1);
        String args = matcher.group(2);

        Optional<Skill> skillOpt = registry.getByName(skillName);
        if (skillOpt.isEmpty()) {
            return userInput;
        }

        try {
            return expand(skillOpt.get(), args);
        } catch (SkillLoadException e) {
            return userInput;
        }
    }

    /**
     * 加载并展开一个已解析的 Skill。
     *
     * @param skill 已选择的 Skill
     * @param args 可选用户参数
     * @return 为模型封装的 Skill 指令
     * @throws SkillLoadException Skill 正文无法加载时抛出
     */
    public String expand(Skill skill, String args) {
        String skillName = skill.name();
        String body = loadSkillBody(skill);
        if (body == null) {
            throw new SkillLoadException("Failed to load Skill body: " + skill.filePath());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<skill name=\"")
                .append(skillName)
                .append("\" location=\"")
                .append(skill.filePath())
                .append("\">\n");
        sb.append("References are relative to ").append(skill.baseDir()).append(".\n");
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("</skill>");

        if (args != null && !args.isBlank()) {
            sb.append('\n').append(args);
        }

        return sb.toString();
    }

    /**
     * 当完整输入为 {@code /skill:name} 命令时返回所选 Skill 名称。
     *
     * @param userInput 原始用户输入
     * @return 存在时返回所选 Skill 名称
     */
    public static Optional<String> extractSkillName(String userInput) {
        if (userInput == null) {
            return Optional.empty();
        }
        Matcher matcher = SKILL_COMMAND.matcher(userInput.trim());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * 从本地 Skill 文件加载正文。
     *
     * @param skill 待加载正文的 Skill
     * @return 正文内容；加载失败时返回 null
     */
    private String loadSkillBody(Skill skill) {
        try {
            String fileContent = Files.readString(skill.filePath(), StandardCharsets.UTF_8);
            return SkillLoader.stripFrontmatter(fileContent);
        } catch (IOException e) {
            return null;
        }
    }
}
