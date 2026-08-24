/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.provider;

import java.util.Objects;

/**
 * 表示可扩展且稳定的模型 Provider 身份。
 *
 * @param value Provider 的唯一字符串标识
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public record ProviderId(String value) {
    public ProviderId {
        Objects.requireNonNull(value, "provider id must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
    }
}
