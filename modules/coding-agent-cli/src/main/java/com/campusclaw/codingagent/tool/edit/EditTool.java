/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.edit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.ops.EditOperations;
import com.campusclaw.codingagent.util.FileMutationQueue;
import com.campusclaw.codingagent.util.PathUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 对文件执行精确文本替换并在必要时回退模糊匹配的底层工具实现。
 * 使用 {@link FileMutationQueue} 串行化同一文件上的并发修改。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class EditTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EditOperations editOperations;
    private final FileMutationQueue mutationQueue;
    private final Path cwd;

    @Autowired
    public EditTool(EditOperations editOperations, FileMutationQueue mutationQueue) {
        this(editOperations, mutationQueue, Path.of(System.getProperty("user.dir")));
    }

    public EditTool(EditOperations editOperations, FileMutationQueue mutationQueue, Path cwd) {
        this.editOperations = editOperations;
        this.mutationQueue = mutationQueue;
        this.cwd = cwd;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String label() {
        return "Edit";
    }

    @Override
    public String description() {
        return "Make exact text replacements in a file. Use oldText/newText for single replacement, or edits[] for multiple disjoint replacements.";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode props = MAPPER.createObjectNode();
        props.set("path", MAPPER.createObjectNode().put("type", "string").put("description", "The file path to edit"));
        props.set(
                "oldText",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "The exact text to find and replace (single-replacement mode)"));
        props.set(
                "newText",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "The replacement text (single-replacement mode)"));

        // 多项编辑参数结构。
        ObjectNode editItemProps = MAPPER.createObjectNode();
        editItemProps.set(
                "oldText",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put(
                                "description",
                                "Exact text for one targeted replacement. Must be unique and non-overlapping."));
        editItemProps.set(
                "newText",
                MAPPER.createObjectNode()
                        .put("type", "string")
                        .put("description", "Replacement text for this targeted edit."));
        ObjectNode editItemSchema = MAPPER.createObjectNode()
                .put("type", "object")
                .<ObjectNode>set("properties", editItemProps)
                .set("required", MAPPER.createArrayNode().add("oldText").add("newText"));
        props.set(
                "edits",
                MAPPER.createObjectNode()
                        .put("type", "array")
                        .<ObjectNode>set("items", editItemSchema)
                        .put(
                                "description",
                                "Multiple disjoint edits. Each matched against original, not incrementally. Do not overlap."));

        return MAPPER.createObjectNode()
                .put("type", "object")
                .<ObjectNode>set("properties", props)
                .set("required", MAPPER.createArrayNode().add("path"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        String pathInput = (String) params.get("path");

        if (pathInput == null || pathInput.isBlank()) {
            return errorResult("Error: path is required");
        }

        Path resolvedPath;
        try {
            resolvedPath = PathUtils.resolveToCwd(pathInput, cwd);
        } catch (SecurityException e) {
            return errorResult("Error: " + e.getMessage());
        }

        if (!editOperations.exists(resolvedPath)) {
            return errorResult("Error: file not found: " + pathInput);
        }

        // 使用 edits 数组的多项编辑模式。
        Object editsParam = params.get("edits");
        if (editsParam instanceof List<?> editsList && !editsList.isEmpty()) {
            if (params.containsKey("oldText") || params.containsKey("newText")) {
                return errorResult("Error: use either edits[] or oldText/newText, not both");
            }
            return mutationQueue.withLock(
                    resolvedPath,
                    () -> performMultiEdit(resolvedPath, pathInput, (List<Map<String, Object>>) editsList));
        }

        // 使用 oldText 和 newText 的单项编辑模式。
        String oldText = (String) params.get("oldText");
        String newText = (String) params.get("newText");

        if (oldText == null) {
            return errorResult("Error: oldText is required (or use edits[] for multi-edit)");
        }
        if (newText == null) {
            return errorResult("Error: newText is required");
        }

        return mutationQueue.withLock(resolvedPath, () -> performEdit(resolvedPath, pathInput, oldText, newText));
    }

    private AgentToolResult performEdit(Path path, String pathInput, String oldText, String newText) throws Exception {
        byte[] rawBytes = editOperations.readFile(path);
        String content = new String(rawBytes, StandardCharsets.UTF_8);

        // 检查精确文本是否重复出现。
        int occurrences = FuzzyMatch.countOccurrences(content, oldText);
        if (occurrences > 1) {
            return errorResult("Error: oldText matches " + occurrences + " occurrences in " + pathInput
                    + ". Provide a more specific match.");
        }

        String updatedContent;
        boolean fuzzyUsed = false;

        if (occurrences == 1) {
            // 精确命中时直接替换。
            updatedContent = content.replace(oldText, newText);
        } else {
            // 未精确命中时尝试模糊匹配。
            FuzzyMatch.Match match = FuzzyMatch.fuzzyFindText(content, oldText);
            if (match == null) {
                return errorResult("Error: oldText not found in " + pathInput);
            }
            updatedContent = content.substring(0, match.start()) + newText + content.substring(match.end());
            fuzzyUsed = true;
        }

        // 写入更新后的内容。
        editOperations.writeFile(path, updatedContent);

        // 生成差异和执行详情。
        String diff = DiffUtils.computeUnifiedDiff(content, updatedContent, pathInput);
        Integer firstChangedLine = DiffUtils.findFirstChangedLine(content, updatedContent);
        var details = new EditToolDetails(diff, firstChangedLine);

        String message = fuzzyUsed ? "Applied edit to " + pathInput + " (fuzzy match)" : "Applied edit to " + pathInput;

        return new AgentToolResult(List.<ContentBlock>of(new TextContent(message)), details);
    }

    /**
     * 基于原始文件内容执行多项互不重叠的编辑，所有编辑都不做增量匹配。
     *
     * @param path the path
     * @param pathInput the pathInput
     * @param editsList the editsList
     * @return the result
     *
     * @throws Exception if the operation fails
     */
    private AgentToolResult performMultiEdit(Path path, String pathInput, List<Map<String, Object>> editsList)
            throws Exception {
        byte[] rawBytes = editOperations.readFile(path);
        String original = new String(rawBytes, StandardCharsets.UTF_8);
        String content = original;

        // 应用前确认所有编辑目标存在且唯一。
        record EditEntry(String oldText, String newText, int position) {}
        var entries = new java.util.ArrayList<EditEntry>();

        for (int i = 0; i < editsList.size(); i++) {
            var edit = editsList.get(i);
            String oldText = (String) edit.get("oldText");
            String newText = (String) edit.get("newText");
            if (oldText == null || newText == null) {
                return errorResult("Error: edits[" + i + "] missing oldText or newText");
            }

            int pos = original.indexOf(oldText);
            if (pos < 0) {
                return errorResult("Error: edits[" + i + "].oldText not found in " + pathInput);
            }
            int count = FuzzyMatch.countOccurrences(original, oldText);
            if (count > 1) {
                return errorResult("Error: edits[" + i + "].oldText matches " + count
                        + " occurrences. Provide a more specific match.");
            }
            entries.add(new EditEntry(oldText, newText, pos));
        }

        // 按位置倒序应用，避免前部替换导致偏移变化。
        entries.sort((a, b) -> Integer.compare(b.position, a.position));

        // 检查编辑区间是否重叠。
        for (int i = 0; i < entries.size() - 1; i++) {
            var curr = entries.get(i);
            var next = entries.get(i + 1);
            if (next.position + next.oldText.length() > curr.position) {
                return errorResult("Error: edits overlap. Merge nearby changes into a single edit.");
            }
        }

        // 从文件末尾向前应用编辑。
        for (var entry : entries) {
            content = content.substring(0, entry.position)
                    + entry.newText
                    + content.substring(entry.position + entry.oldText.length());
        }

        editOperations.writeFile(path, content);

        String diff = DiffUtils.computeUnifiedDiff(original, content, pathInput);
        Integer firstChangedLine = DiffUtils.findFirstChangedLine(original, content);
        var details = new EditToolDetails(diff, firstChangedLine);

        return new AgentToolResult(
                List.<ContentBlock>of(new TextContent("Applied " + entries.size() + " edits to " + pathInput)),
                details);
    }

    private static AgentToolResult errorResult(String message) {
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(message)), null);
    }
}
