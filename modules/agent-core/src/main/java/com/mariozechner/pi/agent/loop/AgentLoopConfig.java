package com.mariozechner.pi.agent.loop;

import com.mariozechner.pi.agent.context.ContextTransformer;
import com.mariozechner.pi.agent.context.DefaultMessageConverter;
import com.mariozechner.pi.agent.context.MessageConverter;
import com.mariozechner.pi.agent.queue.MessageQueue;
import com.mariozechner.pi.agent.tool.ToolExecutionMode;
import com.mariozechner.pi.agent.tool.ToolExecutionPipeline;
import com.mariozechner.pi.ai.PiAiService;
import com.mariozechner.pi.ai.types.Model;
import com.mariozechner.pi.ai.types.SimpleStreamOptions;

import java.util.Objects;

/**
 * Configuration required to run the agent loop.
 */
public record AgentLoopConfig(
    PiAiService piAiService,
    Model model,
    MessageConverter convertToLlm,
    ContextTransformer transformContext,
    ToolExecutionPipeline toolPipeline,
    ToolExecutionMode toolExecutionMode,
    MessageQueue steeringQueue,
    MessageQueue followUpQueue,
    SimpleStreamOptions streamOptions
) {

    public AgentLoopConfig {
        piAiService = Objects.requireNonNull(piAiService, "piAiService");
        model = Objects.requireNonNull(model, "model");
        convertToLlm = convertToLlm != null ? convertToLlm : new DefaultMessageConverter();
        toolPipeline = toolPipeline != null ? toolPipeline : new ToolExecutionPipeline();
        toolExecutionMode = toolExecutionMode != null ? toolExecutionMode : ToolExecutionMode.SEQUENTIAL;
        steeringQueue = steeringQueue != null ? steeringQueue : new MessageQueue();
        followUpQueue = followUpQueue != null ? followUpQueue : new MessageQueue();
        streamOptions = streamOptions != null ? streamOptions : SimpleStreamOptions.empty();
    }
}
