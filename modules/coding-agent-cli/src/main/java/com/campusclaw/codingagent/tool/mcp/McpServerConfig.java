/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP server configuration.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record McpServerConfig(
        String name,
        boolean enabled,
        Transport transport,
        List<String> command,
        String url,
        Map<String, String> env,
        Trust trust,
        String namePrefix,
        ExposeNames exposeNames,
        int startupTimeoutSeconds,
        int callTimeoutSeconds) {

    /**
     * Supported MCP transport types.
     */
    public enum Transport {
        STDIO,
        HTTP
    }

    /**
     * Trust level assigned to a configured MCP server.
     */
    public enum Trust {
        TRUSTED,
        UNTRUSTED
    }

    /**
     * Naming strategy for exposing MCP tools to the LLM.
     */
    public enum ExposeNames {
        PREFIXED,
        RAW
    }

    public McpServerConfig {
        command = List.copyOf(command != null ? command : List.of());
        env = Map.copyOf(env != null ? env : Map.of());
        exposeNames = exposeNames != null ? exposeNames : ExposeNames.PREFIXED;
        trust = trust != null ? trust : Trust.UNTRUSTED;
    }

    public McpServerConfig withNamePrefix(String newPrefix) {
        return new McpServerConfig(
                name, enabled, transport, command, url, env, trust, newPrefix, exposeNames, startupTimeoutSeconds,
                callTimeoutSeconds);
    }

    public McpServerConfig withExposeNames(ExposeNames newExposeNames) {
        return new McpServerConfig(
                name, enabled, transport, command, url, env, trust, namePrefix, newExposeNames, startupTimeoutSeconds,
                callTimeoutSeconds);
    }
}
