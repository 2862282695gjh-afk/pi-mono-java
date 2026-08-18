/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls a tool provided by the Mate tool service. The model emits
 * {@code tool_use("callMateTool", {tool, args})}; this tool forwards the call
 * with agent-handed-down credentials to {@link MateToolClient#callTool} and
 * maps an error result to an exception so ToolExecutionPipeline marks the
 * result {@code isError=true}.
 *
 * <p>Stateless: no metadata is cached between calls; permission enforcement
 * ("deny" rejection) is the Mate server's responsibility.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class CallMateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CallMateTool.class);

    private final MateToolClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode PARAMETERS;

    static {
        try {
            PARAMETERS = MAPPER.readTree(
                    """
                    {"type":"object",
                     "properties":{
                       "tool":{"type":"string","description":"Tool name to call"},
                       "args":{"type":"object","description":"Arguments object for the tool"}
                     },
                     "required":["tool"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse callMateTool schema", e);
        }
    }

    /**
     * Creates a CallMateTool.
     *
     * @param client      the Mate tool service client
     */
    public CallMateTool(MateToolClient client) {
        this.client = client;
    }

    /**
     * Resolves the credentials to forward for a tool invocation. The agent
     * hands down credentials per call; this hook lets the deployment wire its
     * own source (e.g. agent context or config) by overriding.
     *
     * @param tool the tool being called
     * @return credentials forwarded to the Mate server; null means none
     */
    protected MateCredentials resolveCredentials(String tool) {
        return MateCredentials.appKey("", "");
    }

    @Override
    public String name() {
        return "callMateTool";
    }

    @Override
    public String label() {
        return "Call Mate Tool";
    }

    @Override
    public String description() {
        return "Call a tool provided by the Mate service. Use listMateTool first to "
                + "discover available tools and their descriptions.";
    }

    @Override
    public JsonNode parameters() {
        return PARAMETERS;
    }

    @SuppressWarnings("unchecked")
    @Override
    public AgentToolResult execute(
            String toolCallId, Map<String, Object> params, CancellationToken signal, AgentToolUpdateCallback onUpdate)
            throws Exception {

        String tool = (String) params.get("tool");
        Map<String, Object> toolArgs = (Map<String, Object>) params.get("args");

        if (tool == null) {
            throw new IllegalArgumentException("Missing required parameter: tool");
        }

        // ---- call tool ----
        log.info("Calling mate tool: {}", tool);
        MateToolClient.ToolResult result = client.callTool(tool, toolArgs, resolveCredentials(tool));

        if (result.isError()) {
            // Propagate as an exception so ToolExecutionPipeline marks the
            // ToolResultMessage with isError=true (it catches exceptions and
            // builds an error Outcome). Returning normally would lose the
            // error status because the pipeline defaults isError to false.
            throw new MateToolExecutionException(tool, result.content());
        }
        List<ContentBlock> blocks = List.of(new TextContent(result.content()));
        return new AgentToolResult(blocks, result.metadata());
    }

    /**
     * Thrown when the Mate server reports an execution error, so that
     * {@code ToolExecutionPipeline} marks the resulting {@code ToolResultMessage}
     * with {@code isError=true} via its exception catch path.
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/18]
     * @since [br_eCampusCore 26.0.0]
     */
    public static class MateToolExecutionException extends RuntimeException {

        /**
         * Creates the exception.
         *
         * @param tool the tool name that failed
         * @param detail the error detail from the Mate server
         */
        public MateToolExecutionException(String tool, String detail) {
            super("Mate tool '" + tool + "' failed: " + detail);
        }
    }
}
