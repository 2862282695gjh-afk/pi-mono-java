/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client;

import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.tool.mate.CallMateTool.MateCredentials;
import com.campusclaw.codingagent.tool.mate.CallMateTool.MateToolClient;
import com.campusclaw.codingagent.tool.mate.CallMateTool.MateToolMeta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP implementation of {@link MateToolClient}.
 *
 * <p>Each Mate RPC endpoint is a separate protected method with an
 * {@code UnsupportedOperationException} stub — the internal Mate HTTP calls
 * are tracked in {@code docs/DEFERRED.md}.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public class HttpMateToolClient implements MateToolClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMateToolClient.class);

    /**
     * Base URL of the Mate tool server; reserved for the internal Mate HTTP
     * implementation of the four stub methods.
     */
    protected final String baseUrl;

    /**
     * Creates a client pointed at the given Mate tool server.
     *
     * @param baseUrl the Mate tool server base URL (no trailing slash required)
     */
    public HttpMateToolClient(String baseUrl) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public List<MateToolMeta> listTools(String agentId, String skillId, MateCredentials credentials) {
        try {
            List<String> toolIds;
            if (agentId != null) {
                toolIds = queryToolIdsByAgentId(agentId, credentials);
            } else if (skillId != null) {
                toolIds = queryToolIdsBySkillId(skillId, credentials);
            } else {
                log.warn("listTools called without agent_id or skill_id, returning empty list");
                return List.of();
            }
            return queryToolMetaByIds(toolIds, credentials);
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Exception e) {
            log.error("listTools failed: agentId={} skillId={}", agentId, skillId, e);
            throw new IllegalStateException("listTools failed", e);
        }
    }

    @Override
    public ToolResult callTool(String tool, Map<String, Object> args, MateCredentials credentials) {
        try {
            return invokeToolById(tool, args, credentials);
        } catch (Exception e) {
            log.error("callTool failed: tool={}", tool, e);
            return new ToolResult("callTool failed: " + e.getMessage(), null, true);
        }
    }

    // ====================================================================
    // Mate RPC endpoints — stubs for internal development (see DEFERRED.md)
    // ====================================================================

    /**
     * Queries the authorized tool_id list for an agent.
     *
     * @param agentId the Mate agent ID
     * @param credentials Mate authentication credentials
     * @return authorized tool_id list
     * @throws UnsupportedOperationException stub — real Mate call not yet wired
     */
    protected List<String> queryToolIdsByAgentId(String agentId, MateCredentials credentials) {
        throw new UnsupportedOperationException("queryToolIdsByAgentId: stub (see DEFERRED.md)");
    }

    /**
     * Queries the authorized tool_id list for a skill.
     *
     * @param skillId the Mate skill ID
     * @param credentials Mate authentication credentials
     * @return authorized tool_id list
     * @throws UnsupportedOperationException stub — real Mate call not yet wired
     */
    protected List<String> queryToolIdsBySkillId(String skillId, MateCredentials credentials) {
        throw new UnsupportedOperationException("queryToolIdsBySkillId: stub (see DEFERRED.md)");
    }

    /**
     * Queries full tool metadata by tool_id list.
     *
     * @param toolIds the tool_id list to query
     * @param credentials Mate authentication credentials
     * @return full tool metadata list
     * @throws UnsupportedOperationException stub — real Mate call not yet wired
     */
    protected List<MateToolMeta> queryToolMetaByIds(List<String> toolIds, MateCredentials credentials) {
        throw new UnsupportedOperationException("queryToolMetaByIds: stub (see DEFERRED.md)");
    }

    /**
     * Invokes a tool on the Mate server (the real call behind callMateTool).
     *
     * @param toolId the tool ID to invoke
     * @param args the tool arguments
     * @param credentials Mate authentication credentials (id + input credit)
     * @return tool execution result
     * @throws UnsupportedOperationException stub — real Mate call not yet wired
     */
    protected ToolResult invokeToolById(String toolId, Map<String, Object> args, MateCredentials credentials) {
        throw new UnsupportedOperationException("invokeToolById: stub (see DEFERRED.md)");
    }
}
