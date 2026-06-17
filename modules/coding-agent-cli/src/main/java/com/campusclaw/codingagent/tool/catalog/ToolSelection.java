/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.catalog;

import java.util.Arrays;
import java.util.List;

import com.campusclaw.codingagent.settings.Settings;

/**
 * User/session-level tool visibility selection.
 *
 * @version [br_eCampusCore 25.1.0_Next, 2026/06/12]
 * @since [br_eCampusCore 25.1.0_Next]
 */
public record ToolSelection(List<String> include, List<String> exclude, boolean noTools) {

    public ToolSelection {
        include = include != null ? normalize(include) : List.of();
        exclude = exclude != null ? normalize(exclude) : List.of();
    }

    public static ToolSelection all() {
        return new ToolSelection(List.of(), List.of(), false);
    }

    public static ToolSelection fromCli(String toolsFilter, boolean noTools) {
        return fromCli(toolsFilter, noTools, all());
    }

    public static ToolSelection fromCli(String toolsFilter, boolean noTools, ToolSelection defaults) {
        var base = defaults != null ? defaults : all();
        if (toolsFilter == null || toolsFilter.isBlank()) {
            return new ToolSelection(base.include(), base.exclude(), noTools || base.noTools());
        }
        return new ToolSelection(Arrays.asList(toolsFilter.split(",")), List.of(), noTools);
    }

    public static ToolSelection fromSettings(Settings.ToolsSettings settings) {
        if (settings == null) {
            return all();
        }
        boolean disabled = settings.enabled() != null && !settings.enabled();
        return new ToolSelection(
                settings.include(), settings.exclude(), disabled || (settings.noTools() != null && settings.noTools()));
    }

    private static List<String> normalize(List<String> values) {
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}
