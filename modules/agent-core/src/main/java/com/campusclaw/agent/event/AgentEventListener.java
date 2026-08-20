/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.event;

/**
 * Listener for agent runtime events.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface AgentEventListener {

    void onEvent(AgentEvent event);
}
