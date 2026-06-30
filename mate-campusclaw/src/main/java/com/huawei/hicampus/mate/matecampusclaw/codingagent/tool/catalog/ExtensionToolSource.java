/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.tool.catalog;

import java.util.ArrayList;
import java.util.List;

import com.huawei.hicampus.mate.matecampusclaw.codingagent.extension.ExtensionRegistry;

import org.springframework.stereotype.Component;

/**
 * Tool source backed by the in-process extension registry.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
@Component
public class ExtensionToolSource implements ToolSource {

    private final ExtensionRegistry extensionRegistry;

    public ExtensionToolSource(ExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    @Override
    public List<ToolContribution> load(ToolSourceContext context) {
        var contributions = new ArrayList<ToolContribution>();
        for (var extension : extensionRegistry.getAll()) {
            for (var tool : extension.tools()) {
                contributions.add(ToolContribution.add(tool, ToolContributionSource.extension(extension.id()), 200));
            }
        }
        return List.copyOf(contributions);
    }
}
