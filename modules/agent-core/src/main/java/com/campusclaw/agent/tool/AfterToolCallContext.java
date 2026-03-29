package com.campusclaw.agent.tool;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ToolCall;

import java.util.Map;

/**
 * Context passed to the after-tool-call hook.
 */
public record AfterToolCallContext(
    AssistantMessage assistantMessage,
    ToolCall toolCall,
    Map<String, Object> args,
    AgentToolResult result,
    boolean isError,
    AgentContext context
) {
}
