package com.mariozechner.pi.agent.state;

import com.mariozechner.pi.agent.tool.AgentTool;
import com.mariozechner.pi.ai.types.Message;
import com.mariozechner.pi.ai.types.Model;
import com.mariozechner.pi.ai.types.ThinkingLevel;

import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of the current agent state.
 */
public record AgentStateSnapshot(
    String systemPrompt,
    Model model,
    ThinkingLevel thinkingLevel,
    List<AgentTool> tools,
    List<Message> messages,
    boolean streaming,
    Message streamMessage,
    Set<String> pendingToolCalls,
    String error
) {

    public AgentStateSnapshot {
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
        pendingToolCalls = Set.copyOf(pendingToolCalls);
    }
}
