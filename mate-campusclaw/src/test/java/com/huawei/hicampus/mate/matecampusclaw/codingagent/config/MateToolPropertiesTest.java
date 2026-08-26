/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link MateToolProperties} 的默认值与配置前缀测试。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
class MateToolPropertiesTest {

    @Test
    void enabledByDefault() {
        assertThat(new MateToolProperties().isEnabled()).isTrue();
    }

    @Test
    void bindsUnderCampusMateToolNamespace() {
        ConfigurationProperties annotation = MateToolProperties.class.getAnnotation(ConfigurationProperties.class);

        assertThat(annotation.prefix()).isEqualTo("campusmate.tool");
    }
}
