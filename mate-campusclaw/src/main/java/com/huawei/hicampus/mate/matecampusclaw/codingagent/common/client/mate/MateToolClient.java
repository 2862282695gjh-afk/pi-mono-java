/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate;

import java.util.List;
import java.util.Map;

/**
 * Client contract for the Mate tool service. {@code listTools} resolves the
 * bound tool IDs from agent/skill metadata and queries their details,
 * credential-free; {@code callTool} carries {@link MateCredentials} handed
 * down by the agent.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public interface MateToolClient {

    /**
     * Lists tools bound to the given agent or skill: fetches the metadata
     * (binding tool IDs) first, then queries tool details.
     *
     * @param agentId optional agent ID; null = use skillId
     * @param skillId optional skill ID; null = use agentId
     * @return tool metadata list
     */
    List<MateToolMeta> listTools(String agentId, String skillId);

    /**
     * Calls a specific tool with agent-handed-down credentials.
     *
     * @param tool the tool name
     * @param args the tool arguments
     * @param credentials credentials forwarded to the Mate server
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
