package com.mariozechner.pi.agent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mariozechner.pi.ai.stream.AssistantMessageEvent;
import com.mariozechner.pi.ai.types.Message;

/**
 * Emitted for incremental assistant message updates.
 */
public record MessageUpdateEvent(
    @JsonProperty("message") Message message,
    @JsonProperty("assistantMessageEvent") AssistantMessageEvent assistantMessageEvent
) implements AgentEvent {
}
