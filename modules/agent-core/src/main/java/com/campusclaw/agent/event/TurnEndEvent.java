package com.campusclaw.agent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.campusclaw.ai.types.Message;
import com.campusclaw.ai.types.ToolResultMessage;

import java.util.List;

/**
 * Emitted when a turn finishes with the message that ended it and any tool results produced.
 */
public record TurnEndEvent(
    @JsonProperty("message") Message message,
    @JsonProperty("toolResults") List<ToolResultMessage> toolResults
) implements AgentEvent {
}
