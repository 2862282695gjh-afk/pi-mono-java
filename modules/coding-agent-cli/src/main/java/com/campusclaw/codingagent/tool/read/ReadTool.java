/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.read;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.ops.ReadOperations;
import com.campusclaw.codingagent.tool.workspace.AgentWorkspaceBoundary;
import com.campusclaw.codingagent.tool.workspace.WorkspaceAccessException;
import com.campusclaw.codingagent.tool.workspace.WorkspacePathResolver;
import com.campusclaw.codingagent.util.TruncationUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 在当前 Agent 工作区内读取 UTF-8 文本文件的内置工具。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/23]
 * @since [br_eCampusCore 26.0.0]
 */
public class ReadTool implements AgentTool {

    static final int DEFAULT_MAX_BYTES = 50 * 1024;
    static final int DEFAULT_MAX_LINES = 2000;

    private static final String TRUNCATION_NOTICE = "\n\n[Output truncated. Continue with offset and limit.]";
    private static final String FIRST_LINE_TRUNCATION_NOTICE =
            "\n\n[Output truncated: first line exceeds the 50 KB limit.]";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReadOperations readOperations;
    private final WorkspacePathResolver pathResolver;
    private final AgentWorkspaceBoundary boundary;

    public ReadTool(
            ReadOperations readOperations, WorkspacePathResolver pathResolver, AgentWorkspaceBoundary boundary) {
        this.readOperations = readOperations;
        this.pathResolver = pathResolver;
        this.boundary = boundary;
    }

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String label() {
        return "Read";
    }

    @Override
    public String description() {
        return "Read the contents of a UTF-8 text file.";
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
                stringProperty("Path to the file to read, relative or absolute within the current Agent workspace."));
        properties.set("offset", numberProperty("Line number to start reading from, 1-indexed."));
        properties.set("limit", numberProperty("Maximum number of lines to read."));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("path"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        ensureNotCancelled(signal);
        Path path = pathResolver.resolveFile(boundary, (String) params.get("path"));
        byte[] bytes = readOperations.readFile(path);
        ensureNotCancelled(signal);
        return readText(bytes, params);
    }

    private AgentToolResult readText(byte[] bytes, Map<String, Object> params) {
        String content = decodeText(bytes);
        int offset = positiveInt(params.get("offset"), 1, "offset");
        int limit = positiveInt(params.get("limit"), DEFAULT_MAX_LINES, "limit");
        TextSelection selection = selectLines(content, offset, limit);
        boolean firstLineTruncated = firstLineExceedsLimit(selection.text());
        String output = truncateText(selection.text(), selection.moreLines(), firstLineTruncated);
        boolean truncated = selection.moreLines() || !output.equals(selection.text());
        var truncation = truncated ? truncationDetails(selection, output, limit, firstLineTruncated) : null;
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(output)), new ReadToolDetails(truncation));
    }

    private static String decodeText(byte[] bytes) {
        if (containsNull(bytes)) {
            throw new WorkspaceAccessException("Unsupported binary file");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new WorkspaceAccessException("Unsupported binary file", exception);
        }
    }

    private static TextSelection selectLines(String content, int offset, int limit) {
        if (content.isEmpty()) {
            return new TextSelection("", false, 0);
        }
        String[] lines = content.split("\n", -1);
        boolean endsWithNewline = content.endsWith("\n");
        int totalLines = lines.length - (endsWithNewline ? 1 : 0);
        int start = offset - 1;
        if (start >= totalLines) {
            return new TextSelection("", false, totalLines);
        }
        int end = Math.min(start + limit, totalLines);
        String selected = String.join("\n", Arrays.copyOfRange(lines, start, end));
        if (endsWithNewline && end == totalLines) {
            selected += "\n";
        }
        return new TextSelection(selected, end < totalLines, totalLines);
    }

    private static String truncateText(String text, boolean moreLines, boolean firstLineTruncated) {
        String notice = firstLineTruncated ? FIRST_LINE_TRUNCATION_NOTICE : TRUNCATION_NOTICE;
        int noticeBytes = notice.getBytes(StandardCharsets.UTF_8).length;
        int contentBudget = DEFAULT_MAX_BYTES - noticeBytes;
        var truncation = TruncationUtils.truncateTail(text, Integer.MAX_VALUE, contentBudget);
        if (!moreLines && !truncation.truncated()) {
            return text;
        }
        String retained = retainPrefix(text, truncation.outputLines(), contentBudget);
        return retained + notice;
    }

    private static TruncationUtils.TruncationResult truncationDetails(
            TextSelection selection, String output, int limit, boolean firstLineTruncated) {
        int outputLines = output.split("\n", -1).length;
        return new TruncationUtils.TruncationResult(
                true,
                outputLines,
                selection.totalLines(),
                limit,
                DEFAULT_MAX_BYTES,
                firstLineTruncated,
                "lines-or-bytes");
    }

    private static boolean firstLineExceedsLimit(String text) {
        int newline = text.indexOf('\n');
        String firstLine = newline < 0 ? text : text.substring(0, newline);
        int budget = DEFAULT_MAX_BYTES - FIRST_LINE_TRUNCATION_NOTICE.getBytes(StandardCharsets.UTF_8).length;
        return firstLine.getBytes(StandardCharsets.UTF_8).length > budget;
    }

    private static String retainPrefix(String text, int maxLines, int maxBytes) {
        String[] lines = text.split("\n", -1);
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < lines.length && index < maxLines; index++) {
            String separator = index == 0 ? "" : "\n";
            String candidate = output + separator + lines[index];
            if (candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                int used = output.toString().getBytes(StandardCharsets.UTF_8).length
                        + separator.getBytes(StandardCharsets.UTF_8).length;
                int remaining = maxBytes - used;
                if (remaining > 0) {
                    output.append(separator).append(TruncationUtils.truncateLine(lines[index], remaining));
                }
                break;
            }
            output.append(separator).append(lines[index]);
        }
        return output.toString();
    }

    private static int positiveInt(Object value, int defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        int parsed = ((Number) value).intValue();
        if (parsed < 1) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
        return parsed;
    }

    private static boolean containsNull(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode stringProperty(String description) {
        return MAPPER.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode numberProperty(String description) {
        return MAPPER.createObjectNode().put("type", "number").put("description", description);
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool execution was cancelled");
        }
    }

    private record TextSelection(String text, boolean moreLines, int totalLines) {}
}
