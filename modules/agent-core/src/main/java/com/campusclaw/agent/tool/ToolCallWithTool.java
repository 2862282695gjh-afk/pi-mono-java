package com.campusclaw.agent.tool;

import com.campusclaw.ai.types.ToolCall;

import java.util.Map;

/**
 * Bundles a resolved tool implementation with the tool call to execute and its validated arguments.
 */
public record ToolCallWithTool(
    ToolCall toolCall,
    AgentTool tool,
    Map<String, Object> validatedArgs
) {
}
