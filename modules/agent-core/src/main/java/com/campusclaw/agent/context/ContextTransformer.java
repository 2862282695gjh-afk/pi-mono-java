package com.campusclaw.agent.context;

import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronously transforms agent messages before they are sent to the model.
 */
@FunctionalInterface
public interface ContextTransformer {

    CompletableFuture<List<Message>> transform(List<Message> messages, CancellationToken signal);
}
