/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

/**
 * Hook invoked before a tool call is executed.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface BeforeToolCallHandler {

    BeforeToolCallResult handle(BeforeToolCallContext context) throws Exception;
}
