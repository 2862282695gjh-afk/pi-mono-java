/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate;

import java.util.List;
import java.util.Map;

/**
 * Client contract for the Mate tool service. Every method receives
 * {@link MateCredentials} for authentication.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
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
