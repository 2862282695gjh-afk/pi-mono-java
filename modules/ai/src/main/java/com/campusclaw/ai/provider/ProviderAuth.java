/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.ai.provider;

/**
 * 表示 Provider 调用边界采用的应用层认证模式。
 *
 * @param mode 认证模式
 * @version [br_eCampusCore 26.0.0, 2026/08/25]
 * @since [br_eCampusCore 26.0.0]
 */
public record ProviderAuth(Mode mode) {
    public static ProviderAuth none() {
        return new ProviderAuth(Mode.NONE);
    }

    /**
     * 定义当前支持的 Provider 认证模式。
     *
     * @version [br_eCampusCore 26.0.0, 2026/08/25]
     * @since [br_eCampusCore 26.0.0]
     */
    public enum Mode {
        NONE
    }
}
