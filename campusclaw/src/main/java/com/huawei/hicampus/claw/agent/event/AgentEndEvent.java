/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.event;

import java.util.List;

import com.huawei.hicampus.claw.ai.types.Message;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Emitted when an agent run ends with the final message history.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record AgentEndEvent(@JsonProperty("messages") List<Message> messages) implements AgentEvent {}
