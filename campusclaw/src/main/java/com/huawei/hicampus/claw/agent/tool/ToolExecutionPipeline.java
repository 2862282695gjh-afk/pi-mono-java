/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import com.huawei.hicampus.claw.agent.event.AgentEventListener;
import com.huawei.hicampus.claw.agent.event.ToolExecutionEndEvent;
import com.huawei.hicampus.claw.agent.event.ToolExecutionStartEvent;
import com.huawei.hicampus.claw.agent.event.ToolExecutionUpdateEvent;
import com.huawei.hicampus.claw.ai.types.ContentBlock;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.ai.types.ToolResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/**
 * 负责工具调用校验、hook 处理、执行调度和事件投影。
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
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
        throwIfCancelled(signal);
        AgentEventListener eventListener = listener != null ? listener : event -> {};
        var toolName = toolCall.name();
        ToolResultMessage invalid = validate(tool, toolCall, validatedArgs, toolName);
        if (invalid != null) {
            return invalid;
        }
        Preparation preparation = prepare(tool, toolCall, validatedArgs, toolName);
        if (preparation.failure() != null) {
            return preparation.failure();
        }
        PreparedToolInvocation invocation = preparation.invocation();
        ToolResultMessage blocked = applyBeforeHook(toolCall, invocation, context, toolName);
        throwIfCancelled(signal);
        if (blocked != null) {
            return blocked;
        }
        eventListener.onEvent(new ToolExecutionStartEvent(toolCall.id(), toolName, invocation.arguments()));
        var outcome = invokeTool(toolCall, invocation, signal, eventListener);
        throwIfCancelled(signal);
        outcome = applyAfterHook(toolCall, invocation.arguments(), context, outcome);
        eventListener.onEvent(new ToolExecutionEndEvent(toolCall.id(), toolName, outcome.result(), outcome.isError()));
        return toToolResultMessage(toolCall, toolName, outcome.result(), outcome.isError());
    }

    /**
     * 保存阶段之间传递的工具结果及错误标记。
     */
    private record Outcome(AgentToolResult result, boolean isError) {}

    /**
     * 保存准备结果或准备阶段失败。
     */
    private record Preparation(PreparedToolInvocation invocation, ToolResultMessage failure) {}

    // before hook 拒绝或抛出异常时返回错误结果，允许继续执行时返回 null。
    private ToolResultMessage applyBeforeHook(
            ToolCall toolCall, PreparedToolInvocation invocation, AgentContext context, String toolName) {
        try {
            var beforeResult = runBeforeHook(toolCall, invocation, context);
            if (beforeResult != null && beforeResult.block()) {
                String reason = beforeResult.reason() != null
                        ? beforeResult.reason()
                        : "Tool call blocked by beforeToolCall handler";
                return toToolResultMessage(toolCall, toolName, errorResult(reason), true);
            }
            return null;
        } catch (Exception e) {
            return toToolResultMessage(toolCall, toolName, failureResult(e), true);
        }
    }

    private Outcome invokeTool(
            ToolCall toolCall,
            PreparedToolInvocation invocation,
            CancellationToken signal,
            AgentEventListener eventListener) {
        try {
            var result = normalizeResult(invocation.execute(
                    signal,
                    partialResult -> eventListener.onEvent(new ToolExecutionUpdateEvent(
                            toolCall.id(), toolCall.name(), invocation.arguments(), partialResult))));
            return new Outcome(result, false);
        } catch (CancellationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var cancellation = new CancellationException("Tool execution was cancelled");
            cancellation.initCause(e);
            throw cancellation;
        } catch (Exception e) {
            return new Outcome(failureResult(e), true);
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
            return new Outcome(failureResult(e), true);
        }
    }

    public List<ToolResultMessage> executeAll(
            List<ToolCallWithTool> calls, AgentContext context, CancellationToken signal, AgentEventListener listener) {
        Objects.requireNonNull(calls, "calls");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");

        if (calls.isEmpty()) {
            return List.of();
        }

        return executePlanned(calls, context, signal, listener);
    }

    private List<ToolResultMessage> executePlanned(
            List<ToolCallWithTool> calls, AgentContext context, CancellationToken signal, AgentEventListener listener) {
        var results = new ArrayList<ToolResultMessage>(calls.size());
        int index = 0;
        while (index < calls.size()) {
            ToolCallWithTool call = calls.get(index);
            if (call.tool().executionMode() == ToolExecutionMode.SEQUENTIAL) {
                results.add(executeCall(call, context, signal, listener));
                index++;
                continue;
            }
            int segmentEnd = findParallelSegmentEnd(calls, index);
            results.addAll(executeInParallel(calls.subList(index, segmentEnd), context, signal, listener));
            index = segmentEnd;
        }
        return List.copyOf(results);
    }

    private static int findParallelSegmentEnd(List<ToolCallWithTool> calls, int start) {
        int end = start;
        while (end < calls.size() && calls.get(end).tool().executionMode() == ToolExecutionMode.PARALLEL) {
            end++;
        }
        return end;
    }

    private ToolResultMessage executeCall(
            ToolCallWithTool call, AgentContext context, CancellationToken signal, AgentEventListener listener) {
        return execute(call.tool(), call.toolCall(), call.validatedArgs(), context, signal, listener);
    }

    private List<ToolResultMessage> executeInParallel(
            List<ToolCallWithTool> calls, AgentContext context, CancellationToken signal, AgentEventListener listener) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<ToolResultMessage>>(calls.size());
            for (var call : calls) {
                futures.add(executor.submit(() -> executeCall(call, context, signal, listener)));
            }

            var results = new ArrayList<ToolResultMessage>(calls.size());
            for (var future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var cancellation = new CancellationException("Tool execution was cancelled");
            cancellation.initCause(e);
            throw cancellation;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CancellationException cancellation) {
                throw cancellation;
            }
            throw new IllegalStateException("Unexpected failure while executing tools in parallel", e);
        }
    }

    private static void throwIfCancelled(CancellationToken signal) {
        if (signal.isCancelled()) {
            throw new CancellationException("Tool execution was cancelled");
        }
    }

    private BeforeToolCallResult runBeforeHook(
            ToolCall toolCall, PreparedToolInvocation invocation, AgentContext context) throws Exception {
        var handler = beforeToolCallHandler;
        if (handler == null) {
            return null;
        }

        return handler.handle(new BeforeToolCallContext(
                context.assistantMessage(), toolCall, invocation.arguments(), context, invocation));
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

    private ToolResultMessage validate(
            AgentTool tool, ToolCall toolCall, Map<String, Object> validatedArgs, String toolName) {
        try {
            ToolSchemaValidator.validate(
                    objectMapper, schemaFactory, tool.name(), tool.description(), tool.parameters(), validatedArgs);
            return null;
        } catch (Exception error) {
            return toToolResultMessage(toolCall, toolName, failureResult(error), true);
        }
    }

    private Preparation prepare(AgentTool tool, ToolCall toolCall, Map<String, Object> validatedArgs, String toolName) {
        try {
            PreparedToolInvocation invocation = tool.prepare(toolCall.id(), validatedArgs);
            if (invocation == null) {
                throw new IllegalStateException("Tool preparation returned no invocation");
            }
            return new Preparation(invocation, null);
        } catch (CancellationException error) {
            throw error;
        } catch (Exception error) {
            return new Preparation(null, toToolResultMessage(toolCall, toolName, failureResult(error), true));
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

    private AgentToolResult failureResult(Exception e) {
        // 参数错误在模型正文携带修正依据，其他稳定错误只输出错误码；公开边界统一渲染本地化文案。
        if (e instanceof com.huawei.hicampus.claw.agent.error.StableErrorCode coded) {
            java.util.Map<String, Object> details = java.util.Map.of("errorCode", coded.stableErrorCode());
            String content = coded.stableErrorCode();
            if (e instanceof ToolArgumentsInvalidException invalid && invalid.hasRecoveryDefinition()) {
                content = recoveryContent(invalid);
            }
            return new AgentToolResult(List.of(new TextContent(content)), details);
        }
        return errorResult(messageForException(e));
    }

    private String recoveryContent(ToolArgumentsInvalidException failure) {
        var recovery = objectMapper.createObjectNode();
        recovery.put("code", failure.stableErrorCode());
        recovery.put("tool", failure.toolName());
        recovery.put("execution", "not_started");
        var errors = recovery.putArray("errors");
        failure.errors()
                .forEach(error -> errors.addObject().put("path", error.path()).put("message", error.message()));
        recovery.put("definitionComplete", true);
        recovery.set(
                "definition",
                ToolDefinitionFormatter.toNode(
                        objectMapper, failure.toolName(), failure.toolDescription(), failure.inputSchema()));
        String complete = recovery.toString();
        if (complete.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= com.huawei.hicampus.claw.common.constant.ClawConstants.Tool.MAX_DEFINITION_RESULT_BYTES) {
            return complete;
        }
        recovery.remove("definition");
        recovery.put("definitionComplete", false);
        boolean searchAvailable = failure.searchableDefinition()
                && ToolDefinitionFormatter.utf8Length(
                                objectMapper, failure.toolName(), failure.toolDescription(), failure.inputSchema())
                        <= com.huawei.hicampus.claw.common.constant.ClawConstants.Tool.MAX_DEFINITION_RESULT_BYTES;
        recovery.put(
                "message",
                searchAvailable
                        ? "Use SearchTools with the exact tool name from the tool field to retrieve its complete definition."
                        : "The complete definition exceeds the tool result budget and cannot be provided.");
        return recovery.toString();
    }

    private ToolResultMessage toToolResultMessage(
            ToolCall toolCall, String toolName, AgentToolResult result, boolean isError) {
        return new ToolResultMessage(
                toolCall.id(), toolName, result.content(), result.details(), isError, System.currentTimeMillis());
    }

    private String messageForException(Exception e) {
        // 携带稳定错误码的异常公开其错误码,不透传内部诊断文本。
        if (e instanceof com.huawei.hicampus.claw.agent.error.StableErrorCode coded) {
            return coded.stableErrorCode();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
