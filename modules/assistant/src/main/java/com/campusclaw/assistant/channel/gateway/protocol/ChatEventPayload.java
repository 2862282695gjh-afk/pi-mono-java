package com.campusclaw.assistant.channel.gateway.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatEventPayload(
    @JsonProperty("runId") String runId,
    @JsonProperty("session") String session,
    @JsonProperty("seq") int seq,
    @JsonProperty("state") String state,
    @JsonProperty("message") Map<String, Object> message,
    @JsonProperty("toolUse") Object toolUse,
    @JsonProperty("toolResult") Object toolResult,
    @JsonProperty("reason") String reason
) {}
