package com.mariozechner.pi.agent.loop;

import com.mariozechner.pi.ai.stream.AssistantMessageEventStream;
import com.mariozechner.pi.ai.types.Context;
import com.mariozechner.pi.ai.types.Model;
import com.mariozechner.pi.ai.types.SimpleStreamOptions;

/**
 * Functional interface for streaming LLM calls, decoupling the agent loop
 * from the concrete {@link com.mariozechner.pi.ai.PiAiService}.
 *
 * <p>Implementations can wrap PiAiService, add caching, logging, or any
 * other cross-cutting concern.
 */
@FunctionalInterface
public interface StreamFunction {

    /**
     * Starts a streaming LLM call.
     *
     * @param model   the model to invoke
     * @param context the conversation context
     * @param options streaming options (may be null)
     * @return an event stream of assistant message events
     */
    AssistantMessageEventStream stream(Model model, Context context, SimpleStreamOptions options);
}
