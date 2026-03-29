package com.campusclaw.agent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.campusclaw.ai.types.Message;

/**
 * Emitted when a message completes processing or streaming.
 */
public record MessageEndEvent(
    @JsonProperty("message") Message message
) implements AgentEvent {
}
