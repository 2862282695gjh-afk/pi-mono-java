/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.client.mate;

import java.util.Map;

/**
 * Metadata for a single Mate tool, returned by {@code list_tools}.
 *
 * @param name tool name
 * @param description human-readable description
 * @param inputSchema JSON schema for input
 * @param outputSchema JSON schema for output
 * @param isConcurrencySafe whether this tool is safe to run concurrently
 * @param permission "allow", "ask", or "deny"
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record MateToolMeta(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        boolean isConcurrencySafe,
        String permission) {

    /** Permission value: call allowed without asking. */
    public static final String ALLOW = "allow";

    /** Permission value: user approval required before calling. */
    public static final String ASK = "ask";

    /** Permission value: call always rejected. */
    public static final String DENY = "deny";
}
