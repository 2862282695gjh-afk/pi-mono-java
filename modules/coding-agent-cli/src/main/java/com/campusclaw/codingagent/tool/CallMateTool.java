/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.campusclaw.agent.tool.AgentTool;
import com.campusclaw.agent.tool.AgentToolResult;
import com.campusclaw.agent.tool.AgentToolUpdateCallback;
import com.campusclaw.agent.tool.CancellationToken;
import com.campusclaw.ai.types.ContentBlock;
import com.campusclaw.ai.types.TextContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls a tool provided by the Mate tool service. The model emits
 * {@code tool_use("callMateTool", {tool, args})}; this tool looks up the target
 * tool's {@code permission} from cached metadata and enforces allow/ask/deny before
 * forwarding the call to {@link MateToolClient#callTool}.
 *
 * <p>Permission check happens <strong>inside</strong> execute (not in a before-hook)
 * because Mate tools do not pass through {@code ToolExecutionPipeline}.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class CallMateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CallMateTool.class);

    private final MateToolClient client;
    private final MateApprovalUI approvalUI;
    private final MateCredentials credentials;
    private final ConcurrentHashMap<String, MateToolMeta> metaCache = new ConcurrentHashMap<>();

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
     * @param approvalUI  user-approval callback; null for non-interactive
     * @param credentials credentials passed to the Mate server on every call
     */
    public CallMateTool(MateToolClient client, MateApprovalUI approvalUI, MateCredentials credentials) {
        this.client = client;
        this.approvalUI = approvalUI;
        this.credentials = credentials;
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
                + "discover available tools, their descriptions, and permissions.";
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

        // ---- permission check ----
        MateToolMeta meta = metaCache.get(tool);
        String permission = (meta != null && meta.permission() != null) ? meta.permission() : MateToolMeta.ALLOW;

        // ---- validate args against the cached tool inputSchema ----
        if (meta != null && meta.inputSchema() != null && !meta.inputSchema().isEmpty()) {
            validateAgainstSchema(tool, toolArgs, meta.inputSchema());
        }

        log.info("callMateTool: tool={} permission={}", tool, permission);

        if (MateToolMeta.DENY.equals(permission)) {
            throw new MateToolExecutionException(tool, "denied by metadata");
        }

        if (MateToolMeta.ASK.equals(permission)) {
            if (approvalUI == null) {
                log.warn("Approval required but no UI in non-interactive mode, denying: {}", tool);
                throw new MateToolExecutionException(tool, "cannot ask user in non-interactive mode");
            }
            String desc = meta != null ? meta.description() : "Mate tool requires approval";
            boolean approved = approvalUI.ask(tool, toolArgs, desc);
            if (!approved) {
                throw new MateToolExecutionException(tool, "user denied");
            }
        }

        // ---- call tool ----
        log.info("Calling mate tool: {}", tool);
        MateToolClient.ToolResult result = client.callTool(tool, toolArgs, credentials);

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

    // ==================== meta cache ====================

    /**
     * Updates the permission metadata cache. Called by {@link ListMateTool}.
     *
     * @param tools the tool metadata list returned by list_tools
     */
    public void updateMeta(List<MateToolMeta> tools) {
        for (MateToolMeta meta : tools) {
            metaCache.put(meta.name(), meta);
        }
        log.info("Updated mate tool meta cache: count={}", tools.size());
    }

    /**
     * Returns the credentials (for ListMateTool to share).
     *
     * @return the Mate credentials
     */
    public MateCredentials credentials() {
        return credentials;
    }

    /**
     * Validates tool args against the tool's declared JSON Schema (required
     * fields and basic type names). Fails fast before the permission check and
     * the remote call so bad params surface locally, not at the Mate server.
     *
     * @param tool the tool name (for error messages)
     * @param args the tool arguments (may be null, treated as empty)
     * @param schema the tool's JSON Schema object
     * @throws IllegalArgumentException when a required argument is missing or a
     *         value does not match the declared type
     */
    private static void validateAgainstSchema(String tool, Map<String, Object> args, Map<String, Object> schema) {
        Map<String, Object> effective = args != null ? args : Map.of();
        Object requiredRaw = schema.get("required");
        if (requiredRaw instanceof List<?> required) {
            for (Object req : required) {
                if (!effective.containsKey(String.valueOf(req))) {
                    throw new IllegalArgumentException("Tool '" + tool + "' missing required argument: " + req);
                }
            }
        }
        Object propsRaw = schema.get("properties");
        if (propsRaw instanceof Map<?, ?> properties) {
            for (var entry : effective.entrySet()) {
                Object propSchema = properties.get(entry.getKey());
                if (propSchema instanceof Map<?, ?> prop) {
                    Object type = prop.get("type");
                    if (type != null && !matchesType(entry.getValue(), String.valueOf(type))) {
                        throw new IllegalArgumentException("Tool '" + tool + "' argument '"
                                + entry.getKey() + "' expects " + type + ", got "
                                + typeName(entry.getValue()));
                    }
                }
            }
        }
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            default -> true;
        };
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Thrown when the Mate server reports an execution error, so that
     * {@code ToolExecutionPipeline} marks the resulting {@code ToolResultMessage}
     * with {@code isError=true} via its exception catch path.
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/17]
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

    // ==================== Supporting types ====================

    /**
     * Metadata for a single Mate tool, returned by {@code list_tools}.
     *
     * @param name tool name
     * @param description human-readable description
     * @param inputSchema JSON schema for input
     * @param outputSchema JSON schema for output
     * @param isConcurrencySafe whether this tool is safe to run concurrently
     * @param permission "allow", "ask", or "deny"
     */
    public record MateToolMeta(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            boolean isConcurrencySafe,
            String permission) {

        public static final String ALLOW = "allow";
        public static final String ASK = "ask";
        public static final String DENY = "deny";
    }

    /**
     * Credentials for authenticating with the Mate tool server. Two modes:
     * AppKey (X-HW-ID + X-HW-APPKEY) or JWT (X-HW-ID + Authorization Bearer).
     *
     * @param xHwId X-HW-ID header (always required)
     * @param xHwAppKey X-HW-APPKEY header (AppKey mode; null for JWT)
     * @param authorization Authorization header (JWT mode; null for AppKey)
     */
    public record MateCredentials(String xHwId, String xHwAppKey, String authorization) {

        /**
         * Creates AppKey-mode credentials.
         *
         * @param xHwId the X-HW-ID header value
         * @param xHwAppKey the X-HW-APPKEY header value
         * @return AppKey-mode credentials
         */
        public static MateCredentials appKey(String xHwId, String xHwAppKey) {
            return new MateCredentials(xHwId, xHwAppKey, null);
        }

        /**
         * Creates JWT-mode credentials.
         *
         * @param xHwId the X-HW-ID header value
         * @param bearerToken the raw JWT (without "Bearer " prefix)
         * @return JWT-mode credentials
         */
        public static MateCredentials jwt(String xHwId, String bearerToken) {
            return new MateCredentials(xHwId, null, "Bearer " + bearerToken);
        }
    }

    /**
     * Client for the Mate tool service. Every method receives
     * {@link MateCredentials} for authentication.
     */
    public interface MateToolClient {

        /**
         * Lists tools authorized for the given agent or skill.
         *
         * @param agentId     optional agent ID; null = no filter
         * @param skillId     optional skill ID; null = no filter
         * @param credentials authentication credentials
         * @return tool metadata list
         */
        List<MateToolMeta> listTools(String agentId, String skillId, MateCredentials credentials);

        /**
         * Calls a specific tool.
         *
         * @param tool        the tool name
         * @param args        the tool arguments
         * @param credentials authentication credentials
         * @return tool execution result
         */
        ToolResult callTool(String tool, Map<String, Object> args, MateCredentials credentials);

        /**
         * Tool execution result.
         *
         * @param content the textual content returned by the tool
         * @param metadata optional metadata map
         * @param isError whether the result represents an error
         */
        record ToolResult(String content, Map<String, Object> metadata, boolean isError) {}
    }

    /**
     * User-approval callback for tools whose permission is {@code ask}.
     */
    public interface MateApprovalUI {

        /**
         * Ask the user whether to allow the tool call.
         *
         * @param tool the tool name
         * @param args the tool arguments
         * @param description tool description shown to the user
         * @return true to allow, false to deny
         */
        boolean ask(String tool, Map<String, Object> args, String description);
    }
}
