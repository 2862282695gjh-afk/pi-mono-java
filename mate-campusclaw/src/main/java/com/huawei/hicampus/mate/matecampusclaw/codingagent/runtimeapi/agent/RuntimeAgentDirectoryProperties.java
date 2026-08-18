/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.runtimeapi.agent;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Runtime Agent 只读目录配置。
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/08/18]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Data
@Validated
@ConfigurationProperties(prefix = "campusclaw.runtime.agent-directory")
public class RuntimeAgentDirectoryProperties {
    @NotNull
    private Path root = Path.of(System.getProperty("user.home"), ".campusclaw", "runtime", "agents");
}
