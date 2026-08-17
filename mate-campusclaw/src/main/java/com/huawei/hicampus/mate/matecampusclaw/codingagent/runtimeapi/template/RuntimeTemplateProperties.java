/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.template;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Agent Runtime 发布快照根目录配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
@ConfigurationProperties(prefix = "campusclaw.runtime.template")
public class RuntimeTemplateProperties {
    private Path root = Path.of(System.getProperty("user.home"), ".campusclaw", "runtime", "agents");
}
