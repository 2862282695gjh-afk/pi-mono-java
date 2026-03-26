package com.mariozechner.pi.agent.tool;

import com.mariozechner.pi.ai.types.AssistantMessage;
import com.mariozechner.pi.ai.types.Message;

import java.util.List;

/**
 * Minimal execution context exposed to tool pipeline hooks.
 */
public record AgentContext(
    AssistantMessage assistantMessage,
    List<Message> messages
) {

    public AgentContext {
        messages = List.copyOf(messages);
    }

    public AgentContext(AssistantMessage assistantMessage) {
        this(assistantMessage, List.of(assistantMessage));
    }
}
