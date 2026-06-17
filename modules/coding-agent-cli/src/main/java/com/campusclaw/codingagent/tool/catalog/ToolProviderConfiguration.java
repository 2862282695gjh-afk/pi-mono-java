/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.List;

import com.campusclaw.agent.tool.ToolProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges upstream runtime modules to the CLI tool catalog.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/13]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Configuration(proxyBeanMethods = false)
public class ToolProviderConfiguration {

    @Bean
    ToolProvider toolProvider(ToolCatalog toolCatalog) {
        return allowedTools -> toolCatalog.resolve(toSelection(allowedTools));
    }

    private ToolSelection toSelection(List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return ToolSelection.all();
        }
        return new ToolSelection(allowedTools, List.of(), false);
    }
}
