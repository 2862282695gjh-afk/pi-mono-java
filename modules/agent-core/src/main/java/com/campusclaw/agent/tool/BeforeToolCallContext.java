package com.campusclaw.agent.tool;

import com.campusclaw.ai.types.AssistantMessage;
import com.campusclaw.ai.types.ToolCall;

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
