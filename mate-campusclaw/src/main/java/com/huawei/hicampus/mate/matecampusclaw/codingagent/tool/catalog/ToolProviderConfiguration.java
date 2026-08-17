/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.agent.tool.ToolProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges upstream runtime modules to the CLI tool catalog.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
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
