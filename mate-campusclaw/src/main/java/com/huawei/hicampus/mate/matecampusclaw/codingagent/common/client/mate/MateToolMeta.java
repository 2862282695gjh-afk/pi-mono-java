/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.client.mate;

import java.util.Map;

/**
 * Metadata for a single Mate tool, returned by QUERYTOOLS.
 *
 * @param name tool name
 * @param description human-readable description
 * @param inputSchema JSON schema for input
 * @param outputSchema JSON schema for output
 * @param isConcurrencySafe whether this tool is safe to run concurrently
 * @param permission permission declared by the Mate server; allow/ask/deny
 *        enforcement is currently the server's responsibility (the client
 *        passes calls through)
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
public record MateToolMeta(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        boolean isConcurrencySafe,
        String permission) {

    /** Default permission when the server does not declare one. */
    public static final String ALLOW = "allow";
}
