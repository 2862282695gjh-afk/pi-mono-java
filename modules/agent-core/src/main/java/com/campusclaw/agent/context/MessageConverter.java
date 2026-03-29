package com.campusclaw.agent.context;

import com.campusclaw.ai.types.Message;

import java.util.List;

/**
 * Converts agent-managed messages into the message list sent to the LLM.
 */
@FunctionalInterface
public interface MessageConverter {

    List<Message> convert(List<Message> agentMessages);
}
