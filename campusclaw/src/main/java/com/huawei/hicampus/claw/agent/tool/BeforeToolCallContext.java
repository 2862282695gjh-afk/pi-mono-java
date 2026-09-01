/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import java.util.Map;

import com.huawei.hicampus.claw.ai.types.AssistantMessage;
import com.huawei.hicampus.claw.ai.types.ToolCall;

/**
 * Context passed to the before-tool-call hook.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record BeforeToolCallContext(
        AssistantMessage assistantMessage, ToolCall toolCall, Map<String, Object> args, AgentContext context) {}
