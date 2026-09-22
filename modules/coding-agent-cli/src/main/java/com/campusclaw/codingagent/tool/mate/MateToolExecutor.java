/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.error.StableErrorCode;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.PreparedToolInvocation;
import com.campusclaw.agent.tool.ToolArgumentsInvalidException;
import com.campusclaw.agent.tool.ToolSchemaInvalidException;
import com.campusclaw.agent.tool.ToolSchemaValidator;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.mate.MateToolResolver.ResolvedTool;
import com.campusclaw.codingagent.tool.question.UserQuestionPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按可信工具关联统一解析、校验并执行 Mate 业务工具，调用权限由 Host 单独检查。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/21]
 * @since [br_eCampusCore 26.0.0]
 */
public class MateToolExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MateToolExecutor.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final MateToolResolver resolver;

    private final BusinessToolHandler remoteHandler;

    private BusinessToolDispatcher dispatcher;

    MateToolExecutor(MateToolClient client, MateCredentials credentials, MateToolResolver resolver) {
        this.resolver = resolver;
        this.remoteHandler = new MateRemoteToolHandler(client, credentials);
    }

    synchronized void bindBusinessConfirmation(ToolEntryPoint entryPoint, UserQuestionPort questions) {
        if (dispatcher != null) {
            throw new IllegalStateException("business confirmation is already bound");
        }
        dispatcher = BusinessToolDispatcher.assembled(entryPoint, questions, remoteHandler);
    }

    public List<AgentTool> createAgentToolAdapters() {
        return resolver.resolveAgentTools().stream()
                .map(tool -> (AgentTool) new MateToolAdapter(this, tool))
                .toList();
    }

    public AgentToolResult executeAgent(String name, Map<String, Object> args, CancellationToken signal)
            throws Exception {
        ResolvedTool tool = resolver.resolveAgent(name);
        JsonNode inputSchema = mapper.valueToTree(tool.metadata().inputSchema());
        PreparedToolInvocation invocation = prepareValidatedAgent("direct", tool, inputSchema, args);
        return invocation.execute(signal, partial -> {});
    }

    public AgentToolResult executeSkill(String name, Map<String, Object> args, CancellationToken signal)
            throws Exception {
        PreparedToolInvocation invocation = prepareSkill("direct", name, args, callToolArguments(name, args));
        return invocation.execute(signal, partial -> {});
    }

    PreparedToolInvocation prepareAgent(
            String toolCallId, ResolvedTool tool, JsonNode inputSchema, Map<String, Object> args) {
        return prepare(toolCallId, tool.binding().name(), tool, inputSchema, args, args);
    }

    PreparedToolInvocation prepareValidatedAgent(
            String toolCallId, ResolvedTool tool, JsonNode inputSchema, Map<String, Object> args) {
        Map<String, Object> businessArguments = PreparedToolInvocation.snapshotArguments(args);
        validateArguments(tool, inputSchema, businessArguments);
        return prepareAgent(toolCallId, tool, inputSchema, businessArguments);
    }

    PreparedToolInvocation prepareSkill(
            String toolCallId, String name, Map<String, Object> args, Map<String, Object> invocationArguments) {
        ResolvedTool tool = resolver.resolveSkill(name);
        JsonNode inputSchema = mapper.valueToTree(tool.metadata().inputSchema());
        Map<String, Object> businessArguments = PreparedToolInvocation.snapshotArguments(args);
        validateArguments(tool, inputSchema, businessArguments, tool.disclosable());
        return prepare(toolCallId, "CallTool", tool, inputSchema, businessArguments, invocationArguments);
    }

    ResolvedTool resolveSkillDefinition(String name) {
        ResolvedTool tool = resolver.resolveDisclosableSkill(name);
        JsonNode inputSchema = mapper.valueToTree(tool.metadata().inputSchema());
        validateDefinition(tool, inputSchema);
        return tool;
    }

    private PreparedToolInvocation prepare(
            String toolCallId,
            String invocationName,
            ResolvedTool tool,
            JsonNode inputSchema,
            Map<String, Object> args,
            Map<String, Object> invocationArguments) {
        Map<String, Object> businessArguments = PreparedToolInvocation.snapshotArguments(args);
        BusinessToolHandler.InvocationDTO invocation = new BusinessToolHandler.InvocationDTO(
                toolCallId,
                invocationName,
                PreparedToolInvocation.snapshotArguments(invocationArguments),
                tool.binding().id(),
                tool.binding().name(),
                tool.binding().version(),
                tool.metadata().description(),
                inputSchema,
                tool.disclosable(),
                businessArguments);
        BusinessToolHandler handler = dispatcher().select(tool.binding().name());
        return handler.prepare(invocation);
    }

    private synchronized BusinessToolDispatcher dispatcher() {
        if (dispatcher == null) {
            dispatcher = BusinessToolDispatcher.unbound(remoteHandler);
        }
        return dispatcher;
    }

    private void validateArguments(ResolvedTool tool, JsonNode inputSchema, Map<String, Object> args) {
        validateArguments(tool, inputSchema, args, false);
    }

    private void validateArguments(
            ResolvedTool tool, JsonNode inputSchema, Map<String, Object> args, boolean searchableDefinition) {
        try {
            ToolSchemaValidator.validate(
                    mapper,
                    schemaFactory,
                    tool.binding().name(),
                    tool.metadata().description(),
                    inputSchema,
                    args,
                    searchableDefinition);
        } catch (ToolArgumentsInvalidException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            Throwable diagnostic = failure instanceof ToolSchemaInvalidException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            LOGGER.atError()
                    .addKeyValue("operation", "mate.tool.validateArguments")
                    .addKeyValue(
                            "errorCode",
                            com.campusclaw.common.constant.ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE)
                    .addKeyValue("validationReason", "INVALID_TOOL_SCHEMA")
                    .addKeyValue("toolId", tool.binding().id())
                    .setCause(diagnostic)
                    .log("Failed to validate managed business tool arguments");
            throw new InvalidDefinitionException(tool.binding().name());
        }
    }

    private void validateDefinition(ResolvedTool tool, JsonNode inputSchema) {
        try {
            ToolSchemaValidator.validateDefinition(schemaFactory, inputSchema);
        } catch (RuntimeException failure) {
            logInvalidSchema(tool, failure);
            throw new InvalidDefinitionException(tool.binding().name());
        }
    }

    private static void logInvalidSchema(ResolvedTool tool, RuntimeException failure) {
        Throwable diagnostic = failure instanceof ToolSchemaInvalidException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        LOGGER.atError()
                .addKeyValue("operation", "mate.tool.validateDefinition")
                .addKeyValue(
                        "errorCode",
                        com.campusclaw.common.constant.ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE)
                .addKeyValue("validationReason", "INVALID_TOOL_SCHEMA")
                .addKeyValue("toolId", tool.binding().id())
                .setCause(diagnostic)
                .log("Failed to validate managed business tool definition");
    }

    static final class InvalidDefinitionException extends MateToolExecutionException implements StableErrorCode {

        private InvalidDefinitionException(String toolName) {
            super(toolName, "business input schema is invalid");
        }

        @Override
        public String stableErrorCode() {
            return com.campusclaw.common.constant.ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE;
        }
    }

    private static Map<String, Object> callToolArguments(String name, Map<String, Object> args) {
        return Map.of("tool", name, "args", args);
    }
}
