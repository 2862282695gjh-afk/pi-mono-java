package com.campusclaw.agent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.campusclaw.ai.types.Message;

import java.util.List;

/**
 * Emitted when an agent run ends with the final message history.
 */
public record AgentEndEvent(
    @JsonProperty("messages") List<Message> messages
) implements AgentEvent {
}
