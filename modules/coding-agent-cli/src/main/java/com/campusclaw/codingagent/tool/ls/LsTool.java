/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.ls;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.ops.LsOperations;
import com.campusclaw.codingagent.tool.ops.LsOperations.LsEntry;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;
import com.campusclaw.codingagent.util.TruncationUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 在当前 Agent 工作区内列出目录项的内置工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public class LsTool implements AgentTool {

    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 1000;
    static final int MAX_BYTES = 50 * 1024;
    private static final String TRUNCATION_MARKER = "\n... (truncated)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LsOperations lsOperations;
    private final WorkspacePathResolver pathResolver;
    private final AgentWorkspaceBoundary boundary;

    public LsTool(LsOperations lsOperations, WorkspacePathResolver pathResolver, AgentWorkspaceBoundary boundary) {
        this.lsOperations = lsOperations;
        this.pathResolver = pathResolver;
        this.boundary = boundary;
    }

    @Override
    public String name() {
        return "Ls";
    }

    @Override
    public String label() {
        return "Ls";
    }

    @Override
    public String description() {
        return "List directory contents.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    @Override
    public JsonNode parameters() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set(
                "path",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "Directory to list; omitted means the current Agent workspace root."));
        properties.set(
                "limit",
                MAPPER.createObjectNode()
                        .put("type", "number")
                        .put("description", "Maximum number of entries; omitted means 500."));
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<ObjectNode>set("properties", properties)
                .put("additionalProperties", false);
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        ensureNotCancelled(signal);
        Path directory = pathResolver.resolveDirectory(boundary, (String) params.get("path"));
        int limit = normalizeLimit(params.get("limit"));
        List<LsEntry> entries = lsOperations.list(directory);
        entries.sort(Comparator.comparing(LsEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(LsEntry::name));
        ensureNotCancelled(signal);
        return textResult(formatEntries(entries, limit));
    }

    private static String formatEntries(List<LsEntry> entries, int limit) {
        if (entries.isEmpty()) {
            return "(empty directory)";
        }
        StringBuilder output = new StringBuilder();
        int emitted = 0;
        for (LsEntry entry : entries) {
            if (emitted >= limit || !appendWithinBudget(output, displayName(entry))) {
                return output + TRUNCATION_MARKER;
            }
            emitted++;
        }
        return output.toString();
    }

    private static boolean appendWithinBudget(StringBuilder output, String name) {
        String separator = output.isEmpty() ? "" : "\n";
        String candidate = output + separator + name;
        int markerBytes = TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
        if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES - markerBytes) {
            return false;
        }
        output.append(separator).append(name);
        return true;
    }

    private static String displayName(LsEntry entry) {
        String name = "directory".equals(entry.type()) ? entry.name() + "/" : entry.name();
        return TruncationUtils.truncateLine(name, MAX_BYTES);
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

    private static AgentToolResult textResult(String text) {
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(text)), null);
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool execution was cancelled");
        }
    }
}
