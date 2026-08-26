/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.huawei.hicampus.mate.matecampusclaw.agent.event.AgentEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionEndEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionStartEvent;
import com.huawei.hicampus.mate.matecampusclaw.agent.event.ToolExecutionUpdateEvent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.AssistantMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.StopReason;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolCall;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ToolResultMessage;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ToolExecutionPipelineTest {

    @Test
    void executesToolAndEmitsLifecycleEvents() {
        var pipeline = new ToolExecutionPipeline();
        var tool = new MockAgentTool("search", false, false, 0L);
        var toolCall = new ToolCall("call-1", "search", Map.of("query", "java"));
        var context = sampleContext();
        var events = new ArrayList<AgentEvent>();

        var result = pipeline.execute(
                tool, toolCall, Map.of("query", "java"), context, new CancellationToken(), events::add);

        assertFalse(result.isError());
        assertEquals("call-1", result.toolCallId());
        assertEquals("search", result.toolName());
        assertEquals("final:java", text(result.content().getFirst()));
        assertEquals(Map.of("query", "java", "stage", "final"), result.details());

        assertEquals(3, events.size());
        assertInstanceOf(ToolExecutionStartEvent.class, events.get(0));
        assertInstanceOf(ToolExecutionUpdateEvent.class, events.get(1));
        assertInstanceOf(ToolExecutionEndEvent.class, events.get(2));
        assertEquals("search", ((ToolExecutionStartEvent) events.get(0)).toolName());
        assertInstanceOf(AgentToolResult.class, ((ToolExecutionUpdateEvent) events.get(1)).partialResult());
        assertInstanceOf(AgentToolResult.class, ((ToolExecutionEndEvent) events.get(2)).result());
    }

    @Test
    void beforeToolCallCanBlockExecution() {
        var pipeline = new ToolExecutionPipeline();
        var tool = new MockAgentTool("search", false, false, 0L);
        var toolCall = new ToolCall("call-1", "search", Map.of("query", "java"));
        var beforeCalled = new AtomicBoolean(false);
        var afterCalled = new AtomicBoolean(false);
        var events = new ArrayList<AgentEvent>();

        pipeline.setBeforeToolCall(context -> {
            beforeCalled.set(true);
            return BeforeToolCallResult.block("blocked by policy");
        });
        pipeline.setAfterToolCall(context -> {
            afterCalled.set(true);
            return AfterToolCallResult.noOverride();
        });

        var result = pipeline.execute(
                tool, toolCall, Map.of("query", "java"), sampleContext(), new CancellationToken(), events::add);

        assertTrue(beforeCalled.get());
        assertFalse(afterCalled.get());
        assertFalse(tool.executed.get());
        assertTrue(result.isError());
        assertEquals("blocked by policy", text(result.content().getFirst()));
        assertTrue(events.isEmpty());
    }

    @Test
    void stableErrorCodeExceptionIsExposedAsCodeNotDiagnosticText() {
        var pipeline = new ToolExecutionPipeline();
        var tool = new CodedFailingTool();
        var toolCall = new ToolCall("call-1", "coded", Map.of());

        var result = pipeline.execute(tool, toolCall, Map.of(), sampleContext(), new CancellationToken(), e -> {});

        assertTrue(result.isError());
        assertEquals("MATE_RESPONSE_INVALID", text(result.content().getFirst()));
    }

    private static final class CodedFailingTool implements AgentTool {

        @Override
        public String name() {
            return "coded";
        }

        @Override
        public String label() {
            return "coded";
        }

        @Override
        public String description() {
            return "fails with a stable error code";
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate) {
            throw new CodedFailureException();
        }
    }

    private static final class CodedFailureException extends RuntimeException
            implements com.huawei.hicampus.mate.matecampusclaw.agent.error.StableErrorCode {

        private CodedFailureException() {
            super("internal english diagnostic that must not leak");
        }

        @Override
        public String stableErrorCode() {
            return "MATE_RESPONSE_INVALID";
        }
    }

    @Test
    void afterToolCallCanOverrideResult() {
        var pipeline = new ToolExecutionPipeline();
        var tool = new MockAgentTool("search", false, false, 0L);
        var toolCall = new ToolCall("call-1", "search", Map.of("query", "java"));
        var seenIsError = new AtomicReference<Boolean>();

        pipeline.setAfterToolCall(context -> {
            seenIsError.set(context.isError());
            return new AfterToolCallResult(
                    List.of(new TextContent("overridden")), Map.of("source", "after-hook"), true);
        });

        var result = pipeline.execute(
                tool, toolCall, Map.of("query", "java"), sampleContext(), new CancellationToken(), null);

        assertEquals(Boolean.FALSE, seenIsError.get());
        assertTrue(result.isError());
        assertEquals("overridden", text(result.content().getFirst()));
        assertEquals(Map.of("source", "after-hook"), result.details());
    }

    @Test
    void validatesArgumentsAgainstToolSchema() {
        var pipeline = new ToolExecutionPipeline();
        var tool = new MockAgentTool("search", false, false, 0L);
        var toolCall = new ToolCall("call-1", "search", Map.of("query", 1));
        var events = new ArrayList<AgentEvent>();
        var beforeCalled = new AtomicBoolean(false);
        pipeline.setBeforeToolCall(context -> {
            beforeCalled.set(true);
            return BeforeToolCallResult.allow();
        });

        var result = pipeline.execute(
                tool, toolCall, Map.of("query", 1), sampleContext(), new CancellationToken(), events::add);

        assertTrue(result.isError());
        assertTrue(text(result.content().getFirst()).contains("Tool arguments failed validation"));
        assertFalse(beforeCalled.get());
        assertFalse(tool.executed.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void cancelledExecutionStopsBeforeInvokingTool() {
        var pipeline = new ToolExecutionPipeline();
        var tool = new MockAgentTool("search", false, false, 0L);
        var toolCall = new ToolCall("call-1", "search", Map.of("query", "java"));
        var signal = new CancellationToken();
        signal.cancel();

        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> pipeline.execute(tool, toolCall, toolCall.arguments(), sampleContext(), signal, null));

        assertFalse(tool.executed.get());
    }

    @Test
    void executesAllInParallelUsingVirtualThreads() {
        var pipeline = new ToolExecutionPipeline();
        var context = sampleContext();
        var signal = new CancellationToken();
        var maxConcurrency = new AtomicInteger();
        var currentConcurrency = new AtomicInteger();
        var ready = new CountDownLatch(3);

        var calls = List.of(
                new ToolCallWithTool(
                        new ToolCall("call-1", "search", Map.of("query", "one")),
                        new MockAgentTool("search", false, true, 0L, currentConcurrency, maxConcurrency, ready),
                        Map.of("query", "one")),
                new ToolCallWithTool(
                        new ToolCall("call-2", "search", Map.of("query", "two")),
                        new MockAgentTool("search", false, true, 0L, currentConcurrency, maxConcurrency, ready),
                        Map.of("query", "two")),
                new ToolCallWithTool(
                        new ToolCall("call-3", "search", Map.of("query", "three")),
                        new MockAgentTool("search", false, true, 0L, currentConcurrency, maxConcurrency, ready),
                        Map.of("query", "three")));

        var results = pipeline.executeAll(calls, context, signal, null);

        assertEquals(3, results.size());
        assertTrue(maxConcurrency.get() > 1);
        assertEquals(
                List.of("call-1", "call-2", "call-3"),
                results.stream().map(ToolResultMessage::toolCallId).toList());
    }

    @Test
    void sequentialToolIsBarrierBetweenAdjacentParallelSegments() {
        var pipeline = new ToolExecutionPipeline();
        var firstReady = new CountDownLatch(2);
        var secondReady = new CountDownLatch(2);
        var firstCompleted = new AtomicInteger();
        var barrierCompleted = new AtomicBoolean(false);
        var orderViolation = new AtomicBoolean(false);
        var calls = List.of(
                barrierCall("call-1", ToolExecutionMode.PARALLEL, firstReady, firstCompleted, null, orderViolation),
                barrierCall("call-2", ToolExecutionMode.PARALLEL, firstReady, firstCompleted, null, orderViolation),
                barrierCall(
                        "call-3", ToolExecutionMode.SEQUENTIAL, null, firstCompleted, barrierCompleted, orderViolation),
                barrierCall("call-4", ToolExecutionMode.PARALLEL, secondReady, null, barrierCompleted, orderViolation),
                barrierCall("call-5", ToolExecutionMode.PARALLEL, secondReady, null, barrierCompleted, orderViolation));

        var results = pipeline.executeAll(calls, sampleContext(), new CancellationToken(), null);

        assertFalse(orderViolation.get());
        assertTrue(barrierCompleted.get());
        assertEquals(
                List.of("call-1", "call-2", "call-3", "call-4", "call-5"),
                results.stream().map(ToolResultMessage::toolCallId).toList());
        assertTrue(results.stream().noneMatch(ToolResultMessage::isError));
    }

    private ToolCallWithTool barrierCall(
            String id,
            ToolExecutionMode mode,
            CountDownLatch ready,
            AtomicInteger firstCompleted,
            AtomicBoolean barrierCompleted,
            AtomicBoolean orderViolation) {
        var call = new ToolCall(id, id, Map.of());
        return new ToolCallWithTool(
                call, new BarrierTool(mode, ready, firstCompleted, barrierCompleted, orderViolation), Map.of());
    }

    private AgentContext sampleContext() {
        var assistantMessage = new AssistantMessage(
                List.of(new TextContent("assistant")),
                "anthropic-messages",
                "anthropic",
                "claude-opus-4-6",
                null,
                Usage.empty(),
                StopReason.TOOL_USE,
                null,
                1L);
        return new AgentContext(assistantMessage, List.of(assistantMessage));
    }

    private String text(ContentBlock block) {
        return ((TextContent) block).text();
    }

    private static final class MockAgentTool implements AgentTool {

        private final String name;
        private final boolean throwOnExecute;
        private final boolean coordinateForParallelism;
        private final long delayMillis;
        private final AtomicBoolean executed = new AtomicBoolean(false);
        private final AtomicInteger currentConcurrency;
        private final AtomicInteger maxConcurrency;
        private final CountDownLatch ready;
        private final ObjectMapper mapper = new ObjectMapper();

        private MockAgentTool(String name, boolean throwOnExecute, boolean coordinateForParallelism, long delayMillis) {
            this(name, throwOnExecute, coordinateForParallelism, delayMillis, null, null, null);
        }

        private MockAgentTool(
                String name,
                boolean throwOnExecute,
                boolean coordinateForParallelism,
                long delayMillis,
                AtomicInteger currentConcurrency,
                AtomicInteger maxConcurrency,
                CountDownLatch ready) {
            this.name = name;
            this.throwOnExecute = throwOnExecute;
            this.coordinateForParallelism = coordinateForParallelism;
            this.delayMillis = delayMillis;
            this.currentConcurrency = currentConcurrency;
            this.maxConcurrency = maxConcurrency;
            this.ready = ready;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return "Mock tool " + name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return mapper.createObjectNode()
                    .put("type", "object")
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                            "properties",
                            mapper.createObjectNode()
                                    .set("query", mapper.createObjectNode().put("type", "string")))
                    .set("required", mapper.createArrayNode().add("query"));
        }

        @Override
        public ToolExecutionMode executionMode() {
            return coordinateForParallelism ? ToolExecutionMode.PARALLEL : ToolExecutionMode.SEQUENTIAL;
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate)
                throws Exception {
            executed.set(true);

            if (coordinateForParallelism) {
                var concurrency = currentConcurrency.incrementAndGet();
                maxConcurrency.accumulateAndGet(concurrency, Math::max);
                ready.countDown();
                assertTrue(ready.await(500, TimeUnit.MILLISECONDS));
            }

            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                if (throwOnExecute) {
                    throw new IllegalStateException("tool failed");
                }

                onUpdate.onUpdate(new AgentToolResult(
                        List.of(new TextContent("partial:" + params.get("query"))),
                        Map.of("query", params.get("query"), "stage", "partial")));

                return new AgentToolResult(
                        List.of(new TextContent("final:" + params.get("query"))),
                        Map.of("query", params.get("query"), "stage", "final"));
            } finally {
                if (coordinateForParallelism) {
                    currentConcurrency.decrementAndGet();
                }
            }
        }
    }

    private static final class BarrierTool implements AgentTool {

        private final ToolExecutionMode mode;
        private final CountDownLatch ready;
        private final AtomicInteger firstCompleted;
        private final AtomicBoolean barrierCompleted;
        private final AtomicBoolean orderViolation;
        private final ObjectMapper mapper = new ObjectMapper();

        private BarrierTool(
                ToolExecutionMode mode,
                CountDownLatch ready,
                AtomicInteger firstCompleted,
                AtomicBoolean barrierCompleted,
                AtomicBoolean orderViolation) {
            this.mode = mode;
            this.ready = ready;
            this.firstCompleted = firstCompleted;
            this.barrierCompleted = barrierCompleted;
            this.orderViolation = orderViolation;
        }

        @Override
        public String name() {
            return "barrier";
        }

        @Override
        public String label() {
            return name();
        }

        @Override
        public String description() {
            return "Barrier test tool";
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parameters() {
            return mapper.createObjectNode().put("type", "object");
        }

        @Override
        public ToolExecutionMode executionMode() {
            return mode;
        }

        @Override
        public AgentToolResult execute(
                String toolCallId,
                Map<String, Object> params,
                CancellationToken signal,
                AgentToolUpdateCallback onUpdate)
                throws Exception {
            if (mode == ToolExecutionMode.SEQUENTIAL) {
                checkSequentialBarrier();
            } else {
                checkParallelSegment();
            }
            return new AgentToolResult(List.of(new TextContent(toolCallId)), null);
        }

        private void checkSequentialBarrier() {
            if (firstCompleted == null || firstCompleted.get() != 2) {
                orderViolation.set(true);
            }
            barrierCompleted.set(true);
        }

        private void checkParallelSegment() throws InterruptedException {
            if (barrierCompleted != null && !barrierCompleted.get()) {
                orderViolation.set(true);
            }
            ready.countDown();
            if (!ready.await(500L, TimeUnit.MILLISECONDS)) {
                orderViolation.set(true);
            }
            if (firstCompleted != null) {
                firstCompleted.incrementAndGet();
            }
        }
    }
}
