/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

/**
 * 默认服务启动与显式 CLI 分发规则测试。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
class CampusClawApplicationTest {
    @Test
    void applicationCanBeConstructedWithoutCliDependencies() {
        assertThatNoException().isThrownBy(CampusClawApplication::new);
    }

    @Test
    void onlyLeadingCliTokenSelectsCliMode() {
        assertThat(CampusClawCliLauncher.isCliInvocation(new String[] {"cli", "--help"})).isTrue();
        assertThat(CampusClawCliLauncher.isCliInvocation(new String[] {"--help"})).isFalse();
        assertThat(CampusClawCliLauncher.isCliInvocation(new String[0])).isFalse();
    }
}
