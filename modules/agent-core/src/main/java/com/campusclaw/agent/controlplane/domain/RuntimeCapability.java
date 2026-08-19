/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.controlplane.domain;

/**
 * Coarse-grained capability tag used by the runtime scheduler to filter candidate nodes.
 *
 * <p>Each data-plane node advertises its capabilities at registration time. The set is
 * intentionally small and stable: fine-grained negotiation (specific model id,
 * image tag, etc.) belongs to a richer payload sent inside the registration body.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public enum RuntimeCapability {
    MODEL_ANTHROPIC,
    MODEL_OPENAI,
    MODEL_MISTRAL,
    MODEL_CUSTOM,
    TOOL_BASH,
    TOOL_FILE_IO,
    SUBAGENT_ACP,
    SUBAGENT_HTTP,
    SUBAGENT_A2A,
    SUBAGENT_MCP
}
