/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parsed declarative tool definition.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolDeclaration(
        String name,
        String label,
        String description,
        JsonNode inputSchema,
        Execution execution,
        ToolMergeStrategy mergeStrategy,
        String replaces) {

    /**
     * Process execution configuration for a declarative tool.
     *
     * @param type process execution type
     * @param command command argv
     * @param timeoutSeconds execution timeout
     * @param env additional environment variables
     */
    public record Execution(String type, List<String> command, int timeoutSeconds, Map<String, String> env) {

        public Execution {
            command = List.copyOf(command != null ? command : List.of());
            env = Map.copyOf(env != null ? env : Map.of());
        }
    }
}
