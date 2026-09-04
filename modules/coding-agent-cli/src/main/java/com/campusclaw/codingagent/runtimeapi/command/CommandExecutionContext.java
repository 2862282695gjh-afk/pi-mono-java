/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.Locale;
import java.util.Objects;

/**
 * 只读命令执行上下文；Session 观察值与 Help 清单取自同一个请求 Catalog。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class CommandExecutionContext {
    private final Locale locale;

    private final ResolvedCommandCatalog catalog;

    public CommandExecutionContext(Locale locale, ResolvedCommandCatalog catalog) {
        this.locale = Objects.requireNonNull(locale);
        this.catalog = Objects.requireNonNull(catalog);
    }

    public Locale locale() {
        return locale;
    }

    public ResolvedCommandCatalog catalog() {
        return catalog;
    }

    public CommandSessionSnapshotDTO session() {
        return catalog.session();
    }
}
