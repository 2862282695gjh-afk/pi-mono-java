/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Expands {@code /skill:name-here [args]} commands in user input
 * by reading the referenced SKILL.md file and wrapping it in XML format.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class SkillExpander {

    /**
     * Matches {@code /skill:name} at the start, optionally followed by whitespace and args.
     * Group 1 = skill name, Group 2 = optional args (may be null).
     */
    private static final Pattern SKILL_COMMAND = Pattern.compile("^/skill:([a-z0-9-]+)(?:\\s+(.*))?$", Pattern.DOTALL);

    /**
     * If the user input matches {@code /skill:name [args]}, expands it by:
     * <ol>
     *   <li>Looking up the skill by name in the registry</li>
     *   <li>Reading the SKILL.md file and stripping YAML frontmatter</li>
     *   <li>Wrapping the body in an XML {@code <skill>} element</li>
     *   <li>Appending any trailing args</li>
     * </ol>
     * Returns the original input unchanged if it does not match the pattern
     * or the skill is not found.
     *
     * @param userInput the raw user input string
     * @param registry  the skill registry to look up skills
     * @return expanded skill content or original input
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
     * Loads and expands one already-resolved Skill.
     *
     * @param skill selected Skill
     * @param args optional user arguments
     * @return Skill instructions wrapped for the model
     * @throws SkillLoadException when the Skill body cannot be loaded
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
     * Returns the selected Skill name when the whole input is a {@code /skill:name} command.
     *
     * @param userInput raw user input
     * @return selected Skill name, when present
     */
    public static Optional<String> extractSkillName(String userInput) {
        if (userInput == null) {
            return Optional.empty();
        }
        Matcher matcher = SKILL_COMMAND.matcher(userInput.trim());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * Loads the skill body content from the local skill file.
     *
     * @param skill the skill to load body for
     * @return body content, or null if loading fails
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
