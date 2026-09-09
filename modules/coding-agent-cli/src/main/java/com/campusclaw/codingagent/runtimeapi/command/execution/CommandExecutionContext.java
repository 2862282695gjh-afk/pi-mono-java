/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command.execution;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;

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

    private final CommandRuntimeInvocation runtimeInvocation;

    public CommandExecutionContext(Locale locale, ResolvedCommandCatalog catalog) {
        this(locale, catalog, null);
    }

    public CommandExecutionContext(
            Locale locale, ResolvedCommandCatalog catalog, CommandRuntimeInvocation runtimeInvocation) {
        this.locale = Objects.requireNonNull(locale);
        this.catalog = Objects.requireNonNull(catalog);
        this.runtimeInvocation = runtimeInvocation;
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

    public Optional<CommandRuntimeInvocation> runtimeInvocation() {
        return Optional.ofNullable(runtimeInvocation);
    }
}
