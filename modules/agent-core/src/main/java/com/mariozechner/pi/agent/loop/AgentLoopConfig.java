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
 *
 * <p>Supports both the legacy {@link PiAiService} and the new pluggable
 * {@link StreamFunction} for LLM streaming. If {@code streamFunction} is
 * provided, it takes precedence over {@code piAiService}.
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
    SimpleStreamOptions streamOptions,
    StreamFunction streamFunction,
    SteeringMessageSupplier getSteeringMessages,
    SteeringMessageSupplier getFollowUpMessages
) {

    /** Legacy constructor for backward compatibility. */
    public AgentLoopConfig(
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
        this(piAiService, model, convertToLlm, transformContext, toolPipeline,
            toolExecutionMode, steeringQueue, followUpQueue, streamOptions,
            null, null, null);
    }

    public AgentLoopConfig {
        Objects.requireNonNull(model, "model");
        if (piAiService == null && streamFunction == null) {
            throw new IllegalArgumentException("Either piAiService or streamFunction must be provided");
        }
        convertToLlm = convertToLlm != null ? convertToLlm : new DefaultMessageConverter();
        toolPipeline = toolPipeline != null ? toolPipeline : new ToolExecutionPipeline();
        toolExecutionMode = toolExecutionMode != null ? toolExecutionMode : ToolExecutionMode.SEQUENTIAL;
        steeringQueue = steeringQueue != null ? steeringQueue : new MessageQueue();
        followUpQueue = followUpQueue != null ? followUpQueue : new MessageQueue();
        streamOptions = streamOptions != null ? streamOptions : SimpleStreamOptions.empty();
    }

    /**
     * Returns the effective stream function, either the explicitly provided one
     * or one wrapping the PiAiService.
     */
    public StreamFunction effectiveStreamFunction() {
        if (streamFunction != null) return streamFunction;
        return piAiService::streamSimple;
    }
}
