/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.error.StableErrorCode;
import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.agent.tool.ToolDefinitionFormatter;
import com.campusclaw.agent.tool.ToolExecutionMode;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.tool.mate.MateToolExecutor.InvalidDefinitionException;
import com.campusclaw.codingagent.tool.mate.MateToolResolver.ResolutionException;
import com.campusclaw.codingagent.tool.mate.MateToolResolver.ResolutionFailure;
import com.campusclaw.codingagent.tool.mate.MateToolResolver.ResolvedTool;
import com.campusclaw.common.constant.ClawConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 按精确技术名返回当前 Agent 可信直接 Skill 关联的单个完整业务工具定义。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/22]
 * @since [br_eCampusCore 26.0.0]
 */
public class SearchTools implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MateToolExecutor executor;

    SearchTools(MateToolExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    @Override
    public String name() {
        return "SearchTools";
    }

    @Override
    public String label() {
        return "Search Tools";
    }

    @Override
    public String description() {
        return "Get one complete business tool definition by its exact unique name within the current Agent's"
                + " trusted Skill bindings. This does not register or execute the tool.";
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    @Override
    public JsonNode parameters() {
        var tool = MAPPER.createObjectNode();
        tool.put("type", "string");
        tool.put("minLength", 1);
        tool.put("description", "Exact unique business tool name referenced by the Skill instructions.");
        var properties = MAPPER.createObjectNode();
        properties.set("tool", tool);
        var schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("tool"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {
        ensureNotCancelled(signal);
        String toolName = (String) params.get("tool");
        if (toolName == null || toolName.isBlank()) {
            throw new SearchToolsException(ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE);
        }
        ResolvedTool resolved = resolve(toolName);
        JsonNode inputSchema = MAPPER.valueToTree(resolved.metadata().inputSchema());
        String definition = ToolDefinitionFormatter.toNode(
                        MAPPER, resolved.binding().name(), resolved.metadata().description(), inputSchema)
                .toString();
        if (definition.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > ClawConstants.Tool.MAX_DEFINITION_RESULT_BYTES) {
            throw new SearchToolsException(ClawConstants.RuntimeApi.TOOL_DEFINITION_TOO_LARGE_ERROR_CODE);
        }
        return new AgentToolResult(List.<ContentBlock>of(new TextContent(definition)), null);
    }

    private ResolvedTool resolve(String toolName) {
        try {
            return executor.resolveSkillDefinition(toolName);
        } catch (ResolutionException failure) {
            throw new SearchToolsException(codeFor(failure.reason()));
        } catch (InvalidDefinitionException failure) {
            throw new SearchToolsException(ClawConstants.RuntimeApi.TOOL_DEFINITION_INVALID_ERROR_CODE);
        }
    }

    private static String codeFor(ResolutionFailure failure) {
        return switch (failure) {
            case NAME_UNAVAILABLE -> ClawConstants.RuntimeApi.TOOL_NOT_FOUND_ERROR_CODE;
            case CONFLICT -> ClawConstants.RuntimeApi.TOOL_DEFINITION_CONFLICT_ERROR_CODE;
            case METADATA_UNAVAILABLE, SCHEMA_UNAVAILABLE, INVALID_BINDING ->
                ClawConstants.RuntimeApi.TOOL_DEFINITION_UNAVAILABLE_ERROR_CODE;
        };
    }

    private static void ensureNotCancelled(CancellationToken signal) throws InterruptedException {
        if (signal != null && signal.isCancelled()) {
            throw new InterruptedException("Tool search was cancelled");
        }
    }

    private static final class SearchToolsException extends RuntimeException implements StableErrorCode {

        private final String errorCode;

        private SearchToolsException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        @Override
        public String stableErrorCode() {
            return errorCode;
        }
    }
}
