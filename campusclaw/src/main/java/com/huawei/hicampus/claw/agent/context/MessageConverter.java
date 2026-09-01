/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.context;

import java.util.List;

import com.huawei.hicampus.claw.ai.types.Message;

/**
 * Converts agent-managed messages into the message list sent to the LLM.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface MessageConverter {

    List<Message> convert(List<Message> agentMessages);
}
