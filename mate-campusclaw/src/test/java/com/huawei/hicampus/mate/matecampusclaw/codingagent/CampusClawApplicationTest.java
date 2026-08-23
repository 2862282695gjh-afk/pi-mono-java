/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent;

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

/**
 * CampusClaw 仅服务模式入口测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class CampusClawApplicationTest {
    @Test
    void applicationCanBeConstructedWithoutCliDependencies() {
        assertThatNoException().isThrownBy(CampusClawApplication::new);
    }
}
