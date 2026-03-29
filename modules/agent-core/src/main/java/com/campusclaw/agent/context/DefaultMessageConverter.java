package com.campusclaw.agent.context;

import com.campusclaw.ai.types.Message;

import java.util.List;

/**
 * Default converter that passes agent messages straight through to the LLM.
 */
public class DefaultMessageConverter implements MessageConverter {

    @Override
    public List<Message> convert(List<Message> agentMessages) {
        return agentMessages;
    }
}
