/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.agent.tool;

import java.util.List;

import com.huawei.hicampus.claw.ai.types.ContentBlock;

/**
 * Optional overrides returned from the after-tool-call hook.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record AfterToolCallResult(List<ContentBlock> content, Object details, Boolean isError) {

    public static AfterToolCallResult noOverride() {
        return new AfterToolCallResult(null, null, null);
    }
}
