/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution;

import java.util.Locale;
import java.util.Objects;

import com.huawei.hicampus.claw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;

/**
 * 只读命令执行上下文；Session 观察值与定义身份取自同一个请求 Catalog。
 * Help 内容由窄服务从完整 Agent 元数据快照读取，不来自命令清单。
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
