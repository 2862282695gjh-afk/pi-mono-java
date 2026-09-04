/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.campusclaw.codingagent.runtimeapi.command.definition.CommandDefinition;
import com.campusclaw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.campusclaw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

/**
 * 请求级不可变发现清单；列表与名称查找共享同一次来源解析结果。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ResolvedCommandCatalog {
    private final List<ResolvedCommandDTO> commands;

    private final Map<String, CommandDefinition> definitions;

    private final CommandSessionSnapshotDTO session;

    public ResolvedCommandCatalog(
            CommandSessionSnapshotDTO session,
            List<ResolvedCommandDTO> commands,
            Map<String, CommandDefinition> definitions) {
        this.session = session;
        this.commands = List.copyOf(commands);
        this.definitions = Map.copyOf(definitions);
    }

    public CommandSessionSnapshotDTO session() {
        return session;
    }

    public Optional<CommandDefinition> findDefinition(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(definitions.get(name));
    }

    /**
     * 读取按名称排序的本次发现结果。
     *
     * @return 不可变命令列表
     */
    public List<ResolvedCommandDTO> list() {
        return commands;
    }

    /**
     * 按精确名称查询本次发现结果。
     *
     * @param name 不含前导斜杠的命令名
     * @return 匹配的命令，不存在时为空
     */
    public Optional<ResolvedCommandDTO> find(String name) {
        return commands.stream().filter(command -> command.name().equals(name)).findFirst();
    }
}
