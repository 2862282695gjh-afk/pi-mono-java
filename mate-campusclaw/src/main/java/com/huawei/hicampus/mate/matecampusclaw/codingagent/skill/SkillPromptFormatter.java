/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.skill;

import java.util.List;

/**
 * 将可见 Skill 格式化为系统提示词使用的 XML 块。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class SkillPromptFormatter {

    /**
     * 将可见 Skill 格式化为 XML {@code <available_skills>} 块。
     *
     * @param visibleSkills 已完成可见性过滤的 Skill
     * @return XML Skill 列表；空集合返回空字符串
     */
    public static String format(List<Skill> visibleSkills) {
        if (visibleSkills == null || visibleSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("The following skills provide specialized instructions for specific tasks.\n");
        sb.append("Use Read to load a skill's file when the task matches its description.\n");
        sb.append("When a skill file references a relative path, resolve it against the skill directory ");
        sb.append("(parent of SKILL.md / dirname of the path) and keep the path inside the Agent workspace.\n\n");
        sb.append("<available_skills>\n");

        for (Skill skill : visibleSkills) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(escapeXml(skill.name())).append("</name>\n");
            sb.append("    <description>")
                    .append(escapeXml(skill.description()))
                    .append("</description>\n");
            sb.append("    <location>")
                    .append(escapeXml(skill.filePath().toString()))
                    .append("</location>\n");
            sb.append("  </skill>\n");
        }

        sb.append("</available_skills>");
        return sb.toString();
    }

    static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
