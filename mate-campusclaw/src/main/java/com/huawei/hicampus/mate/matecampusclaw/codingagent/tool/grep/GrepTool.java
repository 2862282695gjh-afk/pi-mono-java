/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.grep;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops.GrepOperations;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops.GrepOperations.GrepRequest;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.ops.GrepOperations.GrepResult;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.workspace.WorkspacePathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 在当前 Agent 工作区内搜索文本内容的内置工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public class GrepTool implements AgentTool {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;
    static final int MAX_CONTEXT = 20;
    static final int MAX_BYTES = 50 * 1024;
    private static final String TRUNCATION_MARKER = "\n... (truncated)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GrepOperations grepOperations;
    private final WorkspacePathResolver pathResolver;
    private final AgentWorkspaceBoundary boundary;

    public GrepTool(
            GrepOperations grepOperations, WorkspacePathResolver pathResolver, AgentWorkspaceBoundary boundary) {
        this.grepOperations = grepOperations;
        this.pathResolver = pathResolver;
        this.boundary = boundary;
    }

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String label() {
        return "Grep";
    }

    @Override
    public String description() {
        return "Search file contents for a pattern.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    @Override
    public JsonNode parameters() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set(
                "pattern",
                stringProperty("Search pattern, interpreted as a regular expression unless literal is true."));
        properties.set(
                "path", stringProperty("Directory or file to search; omitted means the current Agent workspace root."));
        properties.set("glob", stringProperty("Optional glob filter for searched files."));
        properties.set("ignoreCase", booleanProperty("Whether matching is case-insensitive; omitted means false."));
        properties.set(
                "literal", booleanProperty("Whether to treat pattern as a literal string; omitted means false."));
        properties.set("context", numberProperty("Lines shown before and after a match; omitted means 0."));
        properties.set("limit", numberProperty("Maximum matches to return; omitted means 100."));
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
        Path searchPath = pathResolver.resolveFileOrDirectory(boundary, stringValue(params.get("path")));
        GrepRequest request = new GrepRequest(
                boundary,
                searchPath,
                (String) params.get("pattern"),
                stringValue(params.get("glob")),
                booleanValue(params.get("ignoreCase")),
                booleanValue(params.get("literal")),
                normalizeContext(params.get("context")),
                normalizeLimit(params.get("limit")));
        return textResult(formatResult(grepOperations.grep(request, signal)));
    }

    private static String formatResult(GrepResult result) {
        if (result.lines().isEmpty()) {
            return "No matches found.";
        }
        StringBuilder output = new StringBuilder();
        boolean truncatedByBytes = false;
        for (String line : result.lines()) {
            String candidate = output + (output.isEmpty() ? "" : "\n") + line;
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
        int limit = value == null ? DEFAULT_LIMIT : ((Number) value).intValue();
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int normalizeContext(Object value) {
        int context = value == null ? 0 : ((Number) value).intValue();
        if (context < 0) {
            throw new IllegalArgumentException("context must not be negative");
        }
        return Math.min(context, MAX_CONTEXT);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean enabled && enabled;
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static ObjectNode stringProperty(String description) {
        return MAPPER.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode numberProperty(String description) {
        return MAPPER.createObjectNode().put("type", "number").put("description", description);
    }

    private static ObjectNode booleanProperty(String description) {
        return MAPPER.createObjectNode().put("type", "boolean").put("description", description);
    }

    private static AgentToolResult textResult(String text) {
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), null);
    }
}
