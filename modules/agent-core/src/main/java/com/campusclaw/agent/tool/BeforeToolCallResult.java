/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.agent.tool;

import java.util.Map;

/**
 * Result returned from the before-tool-call hook.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/05/06]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record BeforeToolCallResult(boolean block, String reason, Map<String, Object> argsOverride) {

    public BeforeToolCallResult {
        argsOverride = argsOverride != null ? Map.copyOf(argsOverride) : null;
    }

    public BeforeToolCallResult(boolean block, String reason) {
        this(block, reason, null);
    }

    public static BeforeToolCallResult allow() {
        return new BeforeToolCallResult(false, null, null);
    }

    public static BeforeToolCallResult allow(Map<String, Object> argsOverride) {
        return new BeforeToolCallResult(false, null, argsOverride);
    }

    public static BeforeToolCallResult block(String reason) {
        return new BeforeToolCallResult(true, reason, null);
    }
}
