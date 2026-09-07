/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import lombok.Getter;

/**
 * 上下文压缩的最小业务结果，不暴露内部执行或事件标识。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public final class CompactionResponseVO {
    private final boolean compacted;

    public CompactionResponseVO(boolean compacted) {
        this.compacted = compacted;
    }
}
