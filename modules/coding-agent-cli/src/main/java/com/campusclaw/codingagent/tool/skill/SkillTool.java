/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.runtime.MateServiceClient.SkillInfo;
import com.campusclaw.codingagent.runtime.PreparedAgentRuntime;
import com.campusclaw.codingagent.skill.SkillLoader;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 按名称加载当前 Agent 直接绑定且模型可见的 Skill 指令和资源路径。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
public class SkillTool implements AgentTool {

    private static final int SAMPLE_LIMIT = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkspacePathResolver pathResolver;

    private final AgentWorkspaceBoundary boundary;

    private final Map<String, SkillInfo> visibleSkills;

    public SkillTool(
            WorkspacePathResolver pathResolver, AgentWorkspaceBoundary boundary, PreparedAgentRuntime runtime) {
        this.pathResolver = java.util.Objects.requireNonNull(pathResolver, "pathResolver");
        this.boundary = java.util.Objects.requireNonNull(boundary, "boundary");
        this.visibleSkills = indexVisibleSkills(runtime);
    }

    @Override
    public String name() {
        return "Skill";
    }

    @Override
    public String label() {
        return "Skill";
    }

    @Override
    public String description() {
        return "Load instructions and resource paths for an available Skill by name.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    @Override
    public JsonNode parameters() {
        var properties = MAPPER.createObjectNode();
        properties.set(
                "name",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "Exact name of an available Skill directly bound to the current Agent."));
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        ensureNotCancelled(signal);
        String skillName = (String) params.get("name");
        SkillInfo skill = visibleSkills.get(skillName);
        if (skill == null) {
            throw new SkillExecutionException("Unknown or unavailable Skill: " + skillName);
        }
        Path baseDirectory = resolveBaseDirectory(skillName);
        List<Path> files = sampleFiles(baseDirectory, signal);
        String output = format(skillName, skill.content(), baseDirectory, files);
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(output)), null);
    }

    private Path resolveBaseDirectory(String skillName) {
        String relative = Path.of(ClawConstants.Runtime.DIRECTORY_NAME)
                .resolve(ClawConstants.Skill.DIRECTORY_NAME)
                .resolve(skillName)
                .toString();
        return pathResolver.resolveDirectory(boundary, relative);
    }

    private List<Path> sampleFiles(Path baseDirectory, CancellationToken signal) throws IOException {
        try (var paths = Files.walk(baseDirectory)) {
            return paths.filter(path -> !path.equals(baseDirectory))
                    .peek(path -> ensureNotCancelledUnchecked(signal))
                    .filter(this::isResourceFile)
                    .map(path -> pathResolver.revalidateDiscoveredPath(boundary, path))
                    .sorted()
                    .limit(SAMPLE_LIMIT)
                    .toList();
        }
    }

    private boolean isResourceFile(Path path) {
        String fileName = path.getFileName().toString();
        if (ClawConstants.Skill.MARKDOWN_FILE_NAME.equals(fileName)
                || ClawConstants.Skill.MANIFEST_FILE_NAME.equals(fileName)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isReadable(path)) {
            throw new SkillExecutionException("Skill resource file is unreadable: " + path);
        }
        return true;
    }

    private static Map<String, SkillInfo> indexVisibleSkills(PreparedAgentRuntime runtime) {
        Map<String, SkillInfo> indexed = new java.util.TreeMap<>();
        for (SkillInfo skill : runtime.skills()) {
            Object disabled = SkillLoader.parseFrontmatter(skill.content()).get("disable-model-invocation");
            if (!Boolean.TRUE.equals(disabled) && indexed.putIfAbsent(skill.name(), skill) != null) {
                throw new IllegalStateException("Duplicate visible Skill name: " + skill.name());
            }
        }
        return Map.copyOf(indexed);
    }

    private static String format(String name, String content, Path baseDirectory, List<Path> files) {
        StringBuilder output = new StringBuilder();
        output.append("<skill_content name=\"").append(escapeXml(name)).append("\">\n");
        output.append(SkillLoader.stripFrontmatter(content).strip()).append("\n\n");
        output.append("Base directory for this skill: ").append(baseDirectory).append('\n');
        output.append("Relative resource paths are relative to this base directory.\n");
        output.append("Note: file list is sampled.\n\n<skill_files>\n");
        files.forEach(path ->
                output.append("<file>").append(escapeXml(path.toString())).append("</file>\n"));
        output.append("</skill_files>\n</skill_content>");
        return output.toString();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool execution was cancelled");
        }
    }

    private static void ensureNotCancelledUnchecked(CancellationToken signal) {
        if (signal != null && signal.isCancelled()) {
            throw new java.util.concurrent.CancellationException("Tool execution was cancelled");
        }
    }

    /**
     * 表示 Skill 名称、可见性或资源读取失败。
     *
     * @version [br_eCampusCore 26.0.0, 2026/09/21]
     * @since [br_eCampusCore 26.0.0]
     */
    public static class SkillExecutionException extends RuntimeException {

        public SkillExecutionException(String message) {
            super(message);
        }
    }
}
