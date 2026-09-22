/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.QUERY_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.SKILL_ID;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.binding;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.meta;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.requiredValueSchema;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.runtime;
import static com.huawei.hicampus.claw.codingagent.tool.mate.MateToolTestSupport.skill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import com.huawei.hicampus.claw.agent.tool.AfterToolCallResult;
import com.huawei.hicampus.claw.agent.tool.AgentContext;
import com.huawei.hicampus.claw.agent.tool.BeforeToolCallResult;
import com.huawei.hicampus.claw.agent.tool.CancellationToken;
import com.huawei.hicampus.claw.agent.tool.ToolArgumentsInvalidException;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionMode;
import com.huawei.hicampus.claw.agent.tool.ToolExecutionPipeline;
import com.huawei.hicampus.claw.ai.types.TextContent;
import com.huawei.hicampus.claw.ai.types.ToolCall;
import com.huawei.hicampus.claw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.test.Log4j2TestAppender;
import com.huawei.hicampus.claw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CallTool} 的可信 Skill 范围、业务 Schema 和单次执行测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
class CallToolTest {

    private MockMateToolClient client;

    private MateCredentials credentials;

    @BeforeEach
    void setUp() {
        client = new MockMateToolClient();
        credentials = MateCredentials.jwt("caller-1", "token-1", "access-token-1");
        client.addTool(meta(QUERY_ID, "Query", requiredValueSchema()));
        client.bindSkill(SKILL_ID, List.of(QUERY_ID));
    }

    @Test
    void shouldPublishFixedOuterContractAndDefaultMissingArgsToEmptyObject() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();

        assertThat(tool.name()).isEqualTo("CallTool");
        assertThat(tool.executionMode()).isEqualTo(ToolExecutionMode.SEQUENTIAL);
        assertThat(tool.parameters().path("required").get(0).asText()).isEqualTo("tool");
        assertThat(tool.parameters().path("properties").has("skill")).isFalse();
        assertThatThrownBy(() -> tool.execute("call", Map.of("tool", "Query"), null, null))
                .isInstanceOf(ToolArgumentsInvalidException.class)
                .hasMessageContaining("arguments failed validation");
        assertThat(client.lastCallArgs()).isNull();
    }

    @Test
    void shouldExecuteOnlySkillBoundToolWithCredentialsAndCacheMetadata() throws Exception {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();

        tool.execute("call-1", Map.of("tool", "Query", "args", Map.of("value", "one")), null, null);
        tool.execute("call-2", Map.of("tool", "Query", "args", Map.of("value", "two")), null, null);

        assertThat(client.agentListCalls()).isZero();
        assertThat(client.skillListCalls()).isEqualTo(1);
        assertThat(client.executeCalls()).isEqualTo(2);
        assertThat(client.lastCalledTool()).isEqualTo(QUERY_ID);
        assertThat(client.lastCallArgs()).isEqualTo(Map.of("value", "two"));
        assertThat(client.lastCallCredentials()).isSameAs(credentials);
    }

    @Test
    void shouldRejectAgentOnlyUnknownFrameworkAndArbitraryIdTargets() {
        CallTool tool = state(List.of(binding(QUERY_ID, "Query", "查询", "allow")), List.of())
                .createCallTool();

        assertRejected(tool, "Query");
        assertRejected(tool, "Read");
        assertRejected(tool, QUERY_ID);
        assertRejected(tool, "https://example.com/tool");
        assertThat(client.skillListCalls()).isZero();
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void businessSchemaFailureShouldSkipBeforeAfterAndHttpCallWithRecoveryDefinition() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger beforeCalls = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        pipeline.setBeforeToolCall(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        pipeline.setAfterToolCall(context -> {
            afterCalls.incrementAndGet();
            return new AfterToolCallResult(null, null, null);
        });
        Map<String, Object> arguments = Map.of("tool", "Query", "args", Map.of());

        var result = pipeline.execute(
                tool,
                new ToolCall("call", "CallTool", arguments),
                arguments,
                new AgentContext(),
                new CancellationToken(),
                null);

        assertThat(result.isError()).isTrue();
        assertThat(beforeCalls).hasValue(0);
        assertThat(afterCalls).hasValue(0);
        assertThat(client.executeCalls()).isZero();
        JsonNode recovery = recovery(result.content().getFirst());
        assertThat(recovery.path("code").asText()).isEqualTo("TOOL_ARGUMENTS_INVALID");
        assertThat(recovery.path("tool").asText()).isEqualTo("Query");
        assertThat(recovery.path("execution").asText()).isEqualTo("not_started");
        assertThat(recovery.path("errors").get(0).path("path").asText()).isEqualTo("/value");
        assertThat(recovery.at("/definition/inputSchema/required/0").asText()).isEqualTo("value");
        assertThat(result.details()).isEqualTo(Map.of("errorCode", "TOOL_ARGUMENTS_INVALID"));
    }

    @Test
    void typeAndEnumFailuresShouldStayBeforeConfirmationAndExecution() {
        client.addTool(meta(QUERY_ID, "Query", enumValueSchema()));
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "ask")))
                .createCallTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger beforeCalls = new AtomicInteger();
        pipeline.setBeforeToolCall(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });

        var wrongType = execute(pipeline, tool, Map.of("value", 1), new CancellationToken());
        var wrongEnum = execute(pipeline, tool, Map.of("value", "future"), new CancellationToken());

        assertThat(wrongType.isError()).isTrue();
        assertThat(wrongEnum.isError()).isTrue();
        assertThat(recovery(wrongType.content().getFirst()).path("errors")).isNotEmpty();
        assertThat(recovery(wrongEnum.content().getFirst()).path("errors")).isNotEmpty();
        assertThat(beforeCalls).hasValue(0);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void outerSchemaFailureShouldReturnOnlyCallToolDefinitionWithoutResolvingTarget() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "ask")))
                .createCallTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger beforeCalls = new AtomicInteger();
        pipeline.setBeforeToolCall(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        Map<String, Object> invalid = Map.of("tool", 1);

        var result = pipeline.execute(
                tool,
                new ToolCall("call", "CallTool", invalid),
                invalid,
                new AgentContext(),
                new CancellationToken(),
                null);

        JsonNode recovery = recovery(result.content().getFirst());
        assertThat(recovery.path("tool").asText()).isEqualTo("CallTool");
        assertThat(recovery.at("/definition/inputSchema/properties/tool/type").asText())
                .isEqualTo("string");
        assertThat(client.skillListCalls()).isZero();
        assertThat(beforeCalls).hasValue(0);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void legalAskShouldConfirmOnceAndExecutePreparedIdAndArguments() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "ask")))
                .createCallTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger confirmations = new AtomicInteger();
        Map<String, Object> businessArgs = new HashMap<>();
        businessArgs.put("value", "fixed");
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("tool", "Query");
        arguments.put("args", businessArgs);
        pipeline.setBeforeToolCall(context -> {
            confirmations.incrementAndGet();
            assertThat(context.preparedInvocation().authorizationTargetId()).isEqualTo(QUERY_ID);
            businessArgs.put("value", "changed");
            client.bindSkill(SKILL_ID, List.of());
            return BeforeToolCallResult.allow();
        });

        var result = pipeline.execute(
                tool,
                new ToolCall("call", "CallTool", arguments),
                arguments,
                new AgentContext(),
                new CancellationToken(),
                null);

        assertThat(result.isError()).isFalse();
        assertThat(confirmations).hasValue(1);
        assertThat(client.executeCalls()).isEqualTo(1);
        assertThat(client.lastCalledTool()).isEqualTo(QUERY_ID);
        assertThat(client.lastCallArgs()).isEqualTo(Map.of("value", "fixed"));
    }

    @Test
    void rejectionAndCancellationShouldNeverExecutePreparedCall() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "ask")))
                .createCallTool();
        ToolExecutionPipeline rejectedPipeline = new ToolExecutionPipeline();
        rejectedPipeline.setBeforeToolCall(context -> BeforeToolCallResult.block("denied"));

        var rejected = execute(rejectedPipeline, tool, Map.of("value", "x"), new CancellationToken());

        assertThat(rejected.isError()).isTrue();
        assertThat(client.executeCalls()).isZero();
        CancellationToken signal = new CancellationToken();
        ToolExecutionPipeline cancelledPipeline = new ToolExecutionPipeline();
        cancelledPipeline.setBeforeToolCall(context -> {
            signal.cancel();
            return BeforeToolCallResult.allow();
        });
        assertThatThrownBy(() -> execute(cancelledPipeline, tool, Map.of("value", "x"), signal))
                .isInstanceOf(CancellationException.class);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void unknownTargetShouldFailBeforeHookWithoutRecoveryDefinition() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "ask")))
                .createCallTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger beforeCalls = new AtomicInteger();
        pipeline.setBeforeToolCall(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        Map<String, Object> arguments = Map.of("tool", "Unknown", "args", Map.of());

        var result = pipeline.execute(
                tool,
                new ToolCall("call", "CallTool", arguments),
                arguments,
                new AgentContext(),
                new CancellationToken(),
                null);

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .isEqualTo(ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE)
                .doesNotContain("definitionComplete");
        assertThat(result.details()).isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE));
        assertThat(beforeCalls).hasValue(0);
        assertThat(client.executeCalls()).isZero();
    }

    @Test
    void cancellationAndMateFailureShouldNotReplayExecution() {
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();

        assertThatThrownBy(() -> tool.execute(
                        "cancelled", Map.of("tool", "Query", "args", Map.of("value", "x")), cancelled, null))
                .isInstanceOf(InterruptedException.class);
        assertThat(client.executeCalls()).isZero();

        client.overrideCallResult(
                new com.huawei.hicampus.claw.codingagent.common.client.mate.MateToolClient.ToolResult("failed", null, true));
        assertThatThrownBy(
                        () -> tool.execute("failed", Map.of("tool", "Query", "args", Map.of("value", "x")), null, null))
                .isInstanceOf(MateToolExecutionException.class);
        assertThat(client.executeCalls()).isEqualTo(1);
    }

    @Test
    void invalidSchemaShouldFailBeforeHookWithoutExposingItsCauseOrExecutingMate() {
        client.addTool(meta(QUERY_ID, "Query", Map.of("$ref", "#/$defs/private-missing")));
        CallTool tool = state(List.of(), List.of(binding(QUERY_ID, "Query", "查询", "allow")))
                .createCallTool();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline();
        AtomicInteger beforeCalls = new AtomicInteger();
        pipeline.setBeforeToolCall(context -> {
            beforeCalls.incrementAndGet();
            return BeforeToolCallResult.allow();
        });
        Map<String, Object> arguments = Map.of("tool", "Query", "args", Map.of("value", "private-argument"));
        Logger logger = (Logger) LogManager.getLogger(MateToolExecutor.class);
        Log4j2TestAppender logs = new Log4j2TestAppender("mate-invalid-schema");
        logs.start();
        logger.addAppender(logs);
        com.huawei.hicampus.claw.ai.types.ToolResultMessage result;
        try {
            result = pipeline.execute(
                    tool,
                    new ToolCall("call", "CallTool", arguments),
                    arguments,
                    new AgentContext(),
                    new CancellationToken(),
                    null);
        } finally {
            logger.removeAppender(logs);
            logs.stop();
        }

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .isEqualTo(ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE)
                .doesNotContain("private-missing", "private-argument");
        assertThat(result.details())
                .isEqualTo(Map.of("errorCode", ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE));
        assertThat(beforeCalls).hasValue(0);
        assertThat(client.executeCalls()).isZero();
        assertThat(logs.events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrown())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Reference /$defs/private-missing cannot be resolved");
            assertThat(event.getContextData().toMap())
                    .containsEntry("operation", "mate.tool.validateArguments")
                    .containsEntry("errorCode", ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE)
                    .containsEntry("validationReason", "INVALID_TOOL_SCHEMA")
                    .containsEntry("toolId", QUERY_ID);
            assertThat(event.getMessage().getFormattedMessage())
                    .doesNotContain("private-argument", "token-1", "access-token-1");
        });
    }

    private MateToolSessionState state(
            List<com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool> agentTools,
            List<com.huawei.hicampus.claw.codingagent.runtime.MateServiceClient.BoundTool> skillTools) {
        PreparedAgentRuntime prepared =
                runtime(agentTools, skillTools.isEmpty() ? List.of() : List.of(skill(skillTools)));
        return new MateToolsetFactory(client).createSession(prepared, credentials);
    }

    private static void assertRejected(CallTool tool, String name) {
        assertThatThrownBy(() -> tool.execute("call", Map.of("tool", name), null, null))
                .isInstanceOf(MateToolExecutionException.class);
    }

    private static com.huawei.hicampus.claw.ai.types.ToolResultMessage execute(
            ToolExecutionPipeline pipeline, CallTool tool, Map<String, Object> args, CancellationToken signal) {
        Map<String, Object> arguments = Map.of("tool", "Query", "args", args);
        return pipeline.execute(
                tool, new ToolCall("call", "CallTool", arguments), arguments, new AgentContext(), signal, null);
    }

    private static Map<String, Object> enumValueSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "string", "enum", List.of("active", "cleared"))),
                "required",
                List.of("value"),
                "additionalProperties",
                false);
    }

    private static JsonNode recovery(com.huawei.hicampus.claw.ai.types.ContentBlock content) {
        try {
            return new ObjectMapper().readTree(((TextContent) content).text());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new AssertionError(error);
        }
    }
}
