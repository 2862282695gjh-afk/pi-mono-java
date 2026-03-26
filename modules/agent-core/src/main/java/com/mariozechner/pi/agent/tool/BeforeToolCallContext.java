package com.mariozechner.pi.agent.tool;

import com.mariozechner.pi.ai.types.AssistantMessage;
import com.mariozechner.pi.ai.types.ToolCall;

import java.util.Map;

/**
 * Context passed to the before-tool-call hook.
 */
public record BeforeToolCallContext(
    AssistantMessage assistantMessage,
    ToolCall toolCall,
    Map<String, Object> args,
    AgentContext context
) {
}
