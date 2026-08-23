/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.find;

import java.nio.charset.StandardCharsets;
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
import com.campusclaw.codingagent.tool.ops.FindOperations;
import com.campusclaw.codingagent.tool.ops.FindOperations.FindResult;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 在当前 Agent 工作区内按 glob 表达式发现文件和目录的内置工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public class FindTool implements AgentTool {

    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 1000;
    static final int MAX_BYTES = 50 * 1024;
    private static final String TRUNCATION_MARKER = "\n... (truncated)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FindOperations findOperations;
    private final WorkspacePathResolver pathResolver;
    private final AgentWorkspaceBoundary boundary;

    public FindTool(
            FindOperations findOperations, WorkspacePathResolver pathResolver, AgentWorkspaceBoundary boundary) {
        this.findOperations = findOperations;
        this.pathResolver = pathResolver;
        this.boundary = boundary;
    }

    @Override
    public String name() {
        return "Find";
    }

    @Override
    public String label() {
        return "Find";
    }

    @Override
    public String description() {
        return "Search for files by glob pattern.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    @Override
    public JsonNode parameters() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("pattern", stringProperty("Glob pattern to match files, for example '*.java' or '**/*.json'."));
        properties.set(
                "path", stringProperty("Directory to search in; omitted means the current Agent workspace root."));
        properties.set("limit", numberProperty("Maximum number of results; omitted means 1000."));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("pattern"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        Path root = pathResolver.resolveDirectory(boundary, (String) params.get("path"));
        String pattern = (String) params.get("pattern");
        int limit = normalizeLimit(params.get("limit"));
        FindResult result = findOperations.find(boundary, root, pattern, limit, signal);
        return textResult(formatResult(result));
    }

    private static String formatResult(FindResult result) {
        if (result.paths().isEmpty()) {
            return "No files found.";
        }
        StringBuilder output = new StringBuilder();
        boolean truncatedByBytes = false;
        for (String path : result.paths()) {
            String candidate = output + (output.isEmpty() ? "" : "\n") + path;
            if (candidate.getBytes(StandardCharsets.UTF_8).length > contentBudget()) {
                truncatedByBytes = true;
                break;
            }
            output.setLength(0);
            output.append(candidate);
        }
        if (result.truncated() || truncatedByBytes) {
            output.append(TRUNCATION_MARKER);
        }
        return output.toString();
    }

    private static int contentBudget() {
        return MAX_BYTES - TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int normalizeLimit(Object value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        int limit = ((Number) value).intValue();
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static ObjectNode stringProperty(String description) {
        return MAPPER.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode numberProperty(String description) {
        return MAPPER.createObjectNode().put("type", "number").put("description", description);
    }

    private static AgentToolResult textResult(String text) {
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), null);
    }
}
