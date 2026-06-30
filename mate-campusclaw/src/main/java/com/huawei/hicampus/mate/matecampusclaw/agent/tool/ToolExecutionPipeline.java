/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEventListener;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionUpdateEvent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/**
 * Executes tool calls with hook processing, validation, and event emission.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/05/06]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public class ToolExecutionPipeline {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private volatile BeforeToolCallHandler beforeToolCallHandler;
    private volatile AfterToolCallHandler afterToolCallHandler;

    public void setBeforeToolCall(BeforeToolCallHandler handler) {
        this.beforeToolCallHandler = handler;
    }

    public void setAfterToolCall(AfterToolCallHandler handler) {
        this.afterToolCallHandler = handler;
    }

    public ToolResultMessage execute(
            AgentTool tool,
            ToolCall toolCall,
            Map<String, Object> validatedArgs,
            AgentContext context,
            CancellationToken signal,
            AgentEventListener listener) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(validatedArgs, "validatedArgs");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        AgentEventListener eventListener = listener != null ? listener : event -> {};
        var toolName = toolCall.name();
        Map<String, Object> preparedArgs;
        try {
            preparedArgs = Map.copyOf(tool.prepareArguments(validatedArgs));
        } catch (Exception e) {
            return toToolResultMessage(toolCall, toolName, errorResult(messageForException(e)), true);
        }

        BeforeHookOutcome beforeOutcome = applyBeforeHook(toolCall, preparedArgs, context, toolName);
        if (beforeOutcome.blocked() != null) {
            return beforeOutcome.blocked();
        }

        var effectiveArgs = beforeOutcome.args();
        eventListener.onEvent(new ToolExecutionStartEvent(toolCall.id(), toolName, effectiveArgs));
        var outcome = invokeTool(tool, toolCall, effectiveArgs, signal, eventListener);
        outcome = applyAfterHook(toolCall, effectiveArgs, context, outcome);
        eventListener.onEvent(new ToolExecutionEndEvent(toolCall.id(), toolName, outcome.result(), outcome.isError()));
        return toToolResultMessage(toolCall, toolName, outcome.result(), outcome.isError());
    }

    /**
     * Composite of an {@link AgentToolResult} and its error flag — internal handoff between phases.
     */
    private record Outcome(AgentToolResult result, boolean isError) {}

    private record BeforeHookOutcome(Map<String, Object> args, ToolResultMessage blocked) {}

    // Returns the synthesized blocking ToolResultMessage if the beforeToolCall hook rejected
    // the call (or threw); null when the call should proceed.
    private BeforeHookOutcome applyBeforeHook(
            ToolCall toolCall, Map<String, Object> validatedArgs, AgentContext context, String toolName) {
        try {
            var beforeResult = runBeforeHook(toolCall, validatedArgs, context);
            if (beforeResult != null && beforeResult.block()) {
                String reason = beforeResult.reason() != null
                        ? beforeResult.reason()
                        : "Tool call blocked by beforeToolCall handler";
                return new BeforeHookOutcome(
                        validatedArgs, toToolResultMessage(toolCall, toolName, errorResult(reason), true));
            }
            if (beforeResult != null && beforeResult.argsOverride() != null) {
                return new BeforeHookOutcome(Map.copyOf(beforeResult.argsOverride()), null);
            }
            return new BeforeHookOutcome(validatedArgs, null);
        } catch (Exception e) {
            return new BeforeHookOutcome(
                    validatedArgs, toToolResultMessage(toolCall, toolName, errorResult(messageForException(e)), true));
        }
    }

    private Outcome invokeTool(
            AgentTool tool,
            ToolCall toolCall,
            Map<String, Object> validatedArgs,
            CancellationToken signal,
            AgentEventListener eventListener) {
        try {
            validateArguments(tool, validatedArgs);
            var result = normalizeResult(tool.execute(
                    toolCall.id(),
                    validatedArgs,
                    signal,
                    partialResult -> eventListener.onEvent(new ToolExecutionUpdateEvent(
                            toolCall.id(), toolCall.name(), validatedArgs, partialResult))));
            return new Outcome(result, false);
        } catch (Exception e) {
            return new Outcome(errorResult(messageForException(e)), true);
        }
    }

    private Outcome applyAfterHook(
            ToolCall toolCall, Map<String, Object> validatedArgs, AgentContext context, Outcome outcome) {
        try {
            var afterResult = runAfterHook(toolCall, validatedArgs, context, outcome.result(), outcome.isError());
            if (afterResult == null) {
                return outcome;
            }
            var newResult = applyAfterResult(outcome.result(), afterResult);
            boolean newIsError = afterResult.isError() != null ? afterResult.isError() : outcome.isError();
            return new Outcome(newResult, newIsError);
        } catch (Exception e) {
            return new Outcome(errorResult(messageForException(e)), true);
        }
    }

    public List<ToolResultMessage> executeAll(
            List<ToolCallWithTool> calls,
            ToolExecutionMode mode,
            AgentContext context,
            CancellationToken signal,
            AgentEventListener listener) {
        Objects.requireNonNull(calls, "calls");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");

        if (calls.isEmpty()) {
            return List.of();
        }

        var effectiveMode = effectiveExecutionMode(calls, mode);
        return switch (effectiveMode) {
            case SEQUENTIAL -> executeSequentially(calls, context, signal, listener);
            case PARALLEL -> executeInParallel(calls, context, signal, listener);
        };
    }

    private ToolExecutionMode effectiveExecutionMode(List<ToolCallWithTool> calls, ToolExecutionMode requestedMode) {
        var mode = requestedMode != null ? requestedMode : ToolExecutionMode.SEQUENTIAL;
        if (mode == ToolExecutionMode.PARALLEL
                && calls.stream()
                        .anyMatch(call -> call.tool().defaultExecutionMode() == ToolExecutionMode.SEQUENTIAL)) {
            return ToolExecutionMode.SEQUENTIAL;
        }
        return mode;
    }

    private List<ToolResultMessage> executeSequentially(
            List<ToolCallWithTool> calls, AgentContext context, CancellationToken signal, AgentEventListener listener) {
        var results = new ArrayList<ToolResultMessage>(calls.size());
        for (var call : calls) {
            results.add(execute(call.tool(), call.toolCall(), call.validatedArgs(), context, signal, listener));
        }
        return List.copyOf(results);
    }

    private List<ToolResultMessage> executeInParallel(
            List<ToolCallWithTool> calls, AgentContext context, CancellationToken signal, AgentEventListener listener) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<ToolResultMessage>>(calls.size());
            for (var call : calls) {
                futures.add(executor.submit(
                        () -> execute(call.tool(), call.toolCall(), call.validatedArgs(), context, signal, listener)));
            }

            var results = new ArrayList<ToolResultMessage>(calls.size());
            for (var future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing tools in parallel", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unexpected failure while executing tools in parallel", e);
        }
    }

    private BeforeToolCallResult runBeforeHook(
            ToolCall toolCall, Map<String, Object> validatedArgs, AgentContext context) throws Exception {
        var handler = beforeToolCallHandler;
        if (handler == null) {
            return null;
        }

        return handler.handle(new BeforeToolCallContext(context.assistantMessage(), toolCall, validatedArgs, context));
    }

    private AfterToolCallResult runAfterHook(
            ToolCall toolCall,
            Map<String, Object> validatedArgs,
            AgentContext context,
            AgentToolResult result,
            boolean isError)
            throws Exception {
        var handler = afterToolCallHandler;
        if (handler == null) {
            return null;
        }

        return handler.handle(new AfterToolCallContext(
                context.assistantMessage(), toolCall, validatedArgs, result, isError, context));
    }

    private void validateArguments(AgentTool tool, Map<String, Object> validatedArgs) {
        var schema = schemaFactory.getSchema(tool.parameters());
        var errors = schema.validate(objectMapper.valueToTree(validatedArgs));
        if (!errors.isEmpty()) {
            var message = errors.stream()
                    .map(Object::toString)
                    .sorted()
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("Unknown schema validation error");
            throw new IllegalArgumentException("Tool arguments failed validation: " + message);
        }
    }

    private AgentToolResult applyAfterResult(AgentToolResult baseResult, AfterToolCallResult afterResult) {
        var content = afterResult.content() != null ? List.copyOf(afterResult.content()) : baseResult.content();
        var details = afterResult.details() != null ? afterResult.details() : baseResult.details();
        return new AgentToolResult(content, details);
    }

    private AgentToolResult normalizeResult(AgentToolResult result) {
        if (result == null) {
            return new AgentToolResult(List.of(), null);
        }

        List<ContentBlock> content = result.content() != null ? List.copyOf(result.content()) : List.of();
        return new AgentToolResult(content, result.details());
    }

    private AgentToolResult errorResult(String message) {
        return new AgentToolResult(List.of(new TextContent(message)), null);
    }

    private ToolResultMessage toToolResultMessage(
            ToolCall toolCall, String toolName, AgentToolResult result, boolean isError) {
        return new ToolResultMessage(
                toolCall.id(), toolName, result.content(), result.details(), isError, System.currentTimeMillis());
    }

    private String messageForException(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
