/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.campusclaw.codingagent.common.client.mate.MateCredentials;
import com.campusclaw.codingagent.common.client.mate.MateToolClient;
import com.campusclaw.codingagent.common.client.mate.MateToolMeta;

/**
 * In-memory mock of {@link MateToolClient} for unit tests. Registered tools
 * are returned when their name appears in the queried tool ID list.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public class MockMateToolClient implements MateToolClient {

    private final Map<String, MateToolMeta> toolsByName = new java.util.HashMap<>();

    private List<String> lastQueriedToolIds;

    private String lastCalledTool;

    private MateCredentials lastCallCredentials;

    private MateToolClient.ToolResult overriddenResult;

    /**
     * Registers a tool.
     *
     * @param meta the tool metadata
     */
    public void addTool(MateToolMeta meta) {
        toolsByName.put(meta.name(), meta);
    }

    /**
     * Returns the tool ID list received by the last listTools call.
     *
     * @return the tool IDs
     */
    public List<String> lastQueriedToolIds() {
        return lastQueriedToolIds;
    }

    /**
     * Returns the tool name received by the last callTool call.
     *
     * @return the tool name
     */
    public String lastCalledTool() {
        return lastCalledTool;
    }

    /**
     * Returns the credentials received by the last callTool call.
     *
     * @return the credentials
     */
    public MateCredentials lastCallCredentials() {
        return lastCallCredentials;
    }

    /**
     * Overrides the result returned by the next callTool invocation regardless
     * of the tool name (used to simulate Mate-side errors).
     *
     * @param result the result to return; null restores normal behavior
     */
    public void overrideCallResult(MateToolClient.ToolResult result) {
        this.overriddenResult = result;
    }

    @Override
    public List<MateToolMeta> listTools(List<String> toolIds) {
        lastQueriedToolIds = new ArrayList<>(toolIds);
        List<MateToolMeta> result = new ArrayList<>();
        if (toolIds != null) {
            for (String id : toolIds) {
                MateToolMeta meta = toolsByName.get(id);
                if (meta != null) {
                    result.add(meta);
                }
            }
        }
        return result;
    }

    @Override
    public ToolResult callTool(String tool, Map<String, Object> args, MateCredentials credentials) {
        lastCalledTool = tool;
        lastCallCredentials = credentials;
        if (overriddenResult != null) {
            return overriddenResult;
        }
        MateToolMeta meta = toolsByName.get(tool);
        if (meta == null) {
            return new ToolResult("unknown tool: " + tool, null, true);
        }
        return new ToolResult("mock:" + tool, null, false);
    }
}
