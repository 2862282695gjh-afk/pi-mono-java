/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.ArrayList;
import java.util.List;

import com.campusclaw.codingagent.extension.ExtensionRegistry;

import org.springframework.stereotype.Component;

/**
 * Tool source backed by the in-process extension registry.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/17]
 * @since [br_eCampusCore 26.0.0]
 */
@Component
public class ExtensionToolSource implements ToolSource {

    private final ExtensionRegistry extensionRegistry;

    public ExtensionToolSource(ExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    @Override
    public List<ToolContribution> load(ToolSource.Context context) {
        var contributions = new ArrayList<ToolContribution>();
        for (var extension : extensionRegistry.getAll()) {
            for (var tool : extension.tools()) {
                contributions.add(ToolContribution.add(tool, ToolContributionSource.extension(extension.id())));
            }
        }
        return List.copyOf(contributions);
    }
}
