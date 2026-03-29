package com.campusclaw.agent.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.campusclaw.ai.stream.AssistantMessageEvent;
import com.campusclaw.ai.types.Message;

/**
 * Emitted for incremental assistant message updates.
 */
public record MessageUpdateEvent(
    @JsonProperty("message") Message message,
    @JsonProperty("assistantMessageEvent") AssistantMessageEvent assistantMessageEvent
) implements AgentEvent {
}
