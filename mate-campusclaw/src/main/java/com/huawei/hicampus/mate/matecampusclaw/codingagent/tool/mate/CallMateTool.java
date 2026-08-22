/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.mate;

import java.util.List;
import java.util.Map;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentTool;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolResult;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.AgentToolUpdateCallback;
import com.huawei.hicampus.mate.matecampusclaw.agent.tool.CancellationToken;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.ContentBlock;
import com.huawei.hicampus.mate.matecampusclaw.ai.types.TextContent;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateCredentials;
import com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate.MateToolClient;
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

    private final MateCredentialResolver credentialResolver;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode PARAMETERS;

    static {
        try {
            PARAMETERS = MAPPER.readTree(
                    """
                    {"type":"object",
                     "properties":{
                       "tool":{"type":"string","description":"Tool ID to call (tool-<32 hex>), as returned by listMateTool"},
                       "args":{"type":"object","description":"Arguments object for the tool"}
                     },
                     "required":["tool"]}""");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse callMateTool schema", e);
        }
    }

    /**
     * 创建 CallMateTool。
     *
     * @param client Mate 工具服务客户端
     * @param credentialResolver 按调用解析凭据的提供者；null 时所有调用
     *        被 fail-closed 拒绝（见 {@code HttpMateToolClient} 的凭据校验）
     */
    public CallMateTool(MateToolClient client, MateCredentialResolver credentialResolver) {
        this.client = client;
        this.credentialResolver = credentialResolver;
    }

    /**
     * 解析本次工具调用要透传的凭据：以 {@link MateCredentialResolver.MateToolCall}
     * 上下文快照委托给构造期注入的解析器（每次调用重新解析，按调用隔离）；
     * 未注入解析器时返回 null，由客户端拒绝执行。
     *
     * @param toolCallId 本次工具调用标识
     * @param tool 待调用的工具标识
     * @param args 工具参数
     * @return 透传给 Mate 服务端的凭据；null 表示未接线
     */
    protected MateCredentials resolveCredentials(String toolCallId, String tool, Map<String, Object> args) {
        if (credentialResolver == null) {
            return null;
        }
        return credentialResolver.resolve(new MateCredentialResolver.MateToolCall(toolCallId, tool, args));
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
        MateToolClient.ToolResult result =
                client.callTool(tool, toolArgs, resolveCredentials(toolCallId, tool, toolArgs));

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
