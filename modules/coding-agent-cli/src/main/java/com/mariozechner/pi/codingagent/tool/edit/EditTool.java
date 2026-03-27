package com.mariozechner.pi.codingagent.tool.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mariozechner.pi.agent.tool.AgentTool;
import com.mariozechner.pi.agent.tool.AgentToolResult;
import com.mariozechner.pi.agent.tool.AgentToolUpdateCallback;
import com.mariozechner.pi.agent.tool.CancellationToken;
import com.mariozechner.pi.ai.types.ContentBlock;
import com.mariozechner.pi.ai.types.TextContent;
import com.mariozechner.pi.codingagent.tool.ops.EditOperations;
import com.mariozechner.pi.codingagent.util.FileMutationQueue;
import com.mariozechner.pi.codingagent.util.PathUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Agent tool that performs exact text replacement in files.
 * Falls back to fuzzy matching when exact match fails.
 * Uses {@link FileMutationQueue} to serialize concurrent edits to the same file.
 */
@Component
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
        return "Make an exact text replacement in a file. Specify the old text to find and new text to replace it with.";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode props = MAPPER.createObjectNode();
        props.set("path", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "The file path to edit"));
        props.set("oldText", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "The exact text to find and replace"));
        props.set("newText", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "The replacement text"));

        return MAPPER.createObjectNode()
                .put("type", "object")
                .<ObjectNode>set("properties", props)
                .set("required", MAPPER.createArrayNode().add("path").add("oldText").add("newText"));
    }

    @Override
    public AgentToolResult execute(
            String toolCallId,
            Map<String, Object> params,
            CancellationToken signal,
            AgentToolUpdateCallback onUpdate
    ) throws Exception {
        String pathInput = (String) params.get("path");
        String oldText = (String) params.get("oldText");
        String newText = (String) params.get("newText");

        if (pathInput == null || pathInput.isBlank()) {
            return errorResult("Error: path is required");
        }
        if (oldText == null) {
            return errorResult("Error: oldText is required");
        }
        if (newText == null) {
            return errorResult("Error: newText is required");
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

        return mutationQueue.withLock(resolvedPath, () -> performEdit(resolvedPath, pathInput, oldText, newText));
    }

    private AgentToolResult performEdit(Path path, String pathInput, String oldText, String newText) throws Exception {
        byte[] rawBytes = editOperations.readFile(path);
        String content = new String(rawBytes, StandardCharsets.UTF_8);

        // Check for multiple exact occurrences
        int occurrences = FuzzyMatch.countOccurrences(content, oldText);
        if (occurrences > 1) {
            return errorResult("Error: oldText matches " + occurrences
                    + " occurrences in " + pathInput + ". Provide a more specific match.");
        }

        String updatedContent;
        boolean fuzzyUsed = false;

        if (occurrences == 1) {
            // Exact match — simple replacement
            updatedContent = content.replace(oldText, newText);
        } else {
            // No exact match — try fuzzy
            FuzzyMatch.Match match = FuzzyMatch.fuzzyFindText(content, oldText);
            if (match == null) {
                return errorResult("Error: oldText not found in " + pathInput);
            }
            updatedContent = content.substring(0, match.start()) + newText + content.substring(match.end());
            fuzzyUsed = true;
        }

        // Write the updated content
        editOperations.writeFile(path, updatedContent);

        // Generate diff and details
        String diff = DiffUtils.computeUnifiedDiff(content, updatedContent, pathInput);
        Integer firstChangedLine = DiffUtils.findFirstChangedLine(content, updatedContent);
        var details = new EditToolDetails(diff, firstChangedLine);

        String message = fuzzyUsed
                ? "Applied edit to " + pathInput + " (fuzzy match)"
                : "Applied edit to " + pathInput;

        return new AgentToolResult(
                List.<ContentBlock>of(new TextContent(message)),
                details
        );
    }

    private static AgentToolResult errorResult(String message) {
        return new AgentToolResult(
                List.<ContentBlock>of(new TextContent(message)),
                null
        );
    }
}
