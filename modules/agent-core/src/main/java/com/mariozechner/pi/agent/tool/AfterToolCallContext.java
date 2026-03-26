package com.mariozechner.pi.agent.tool;

import com.mariozechner.pi.ai.types.AssistantMessage;
import com.mariozechner.pi.ai.types.ToolCall;

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
