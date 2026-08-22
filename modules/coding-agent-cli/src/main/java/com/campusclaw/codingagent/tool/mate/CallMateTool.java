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

    private final MateCredentialResolver credentialResolver;

    private final MateToolSessionCache sessionCache;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode PARAMETERS;

    static {
        try {
            PARAMETERS = MAPPER.readTree(
                    """
                    {"type":"object",
                     "properties":{
                       "tool":{"type":"string","description":"Tool name to call, as returned by listMateTool"},
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
     * @param sessionCache 会话级工具名→标识映射缓存；null（非会话单例场景）
     *        时按名调用直接拒绝并提示先调用 listMateTool
     */
    public CallMateTool(
            MateToolClient client, MateCredentialResolver credentialResolver, MateToolSessionCache sessionCache) {
        this.client = client;
        this.credentialResolver = credentialResolver;
        this.sessionCache = sessionCache;
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

        String toolName = (String) params.get("tool");
        Map<String, Object> toolArgs = (Map<String, Object>) params.get("args");

        if (toolName == null) {
            throw new IllegalArgumentException("Missing required parameter: tool");
        }

        // ---- session-scoped name -> id resolution ----
        String toolId = sessionCache != null ? sessionCache.lookupToolId(toolName) : null;
        if (toolId == null) {
            throw new MateToolExecutionException(
                    toolName,
                    sessionCache != null
                            ? "tool name not in the session cache; call listMateTool first to refresh"
                            : "no session cache wired (singleton tool); call listMateTool first");
        }

        // ---- call tool ----
        log.info("Calling mate tool: name={} id={}", toolName, toolId);
        MateToolClient.ToolResult result =
                client.callTool(toolId, toolArgs, resolveCredentials(toolCallId, toolId, toolArgs));

        if (result.isError()) {
            // Propagate as an exception so ToolExecutionPipeline marks the
            // ToolResultMessage with isError=true (it catches exceptions and
            // builds an error Outcome). Returning normally would lose the
            // error status because the pipeline defaults isError to false.
            throw new MateToolExecutionException(toolName, result.content());
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
