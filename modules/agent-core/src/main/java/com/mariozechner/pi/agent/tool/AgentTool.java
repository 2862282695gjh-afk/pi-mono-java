package com.mariozechner.pi.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Executable tool contract used by the agent runtime.
 */
public interface AgentTool {

    String name();

    String label();

    String description();

    JsonNode parameters();

    AgentToolResult execute(
        String toolCallId,
        Map<String, Object> params,
        CancellationToken signal,
        AgentToolUpdateCallback onUpdate
    ) throws Exception;
}
