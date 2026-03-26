package com.mariozechner.pi.agent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mariozechner.pi.ai.types.Message;

/**
 * Emitted when a message enters processing or streaming.
 */
public record MessageStartEvent(
    @JsonProperty("message") Message message
) implements AgentEvent {
}
