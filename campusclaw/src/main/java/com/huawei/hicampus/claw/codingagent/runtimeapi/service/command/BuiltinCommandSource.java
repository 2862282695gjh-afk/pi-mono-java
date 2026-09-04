/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.util.List;
import java.util.TreeMap;

import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandContributor;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.builtin.BuiltinCommandDefinition;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.definition.CommandDefinition;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.source.CommandDefinitionSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandKind;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

import org.springframework.stereotype.Service;

/**
 * 启动时聚合 Builtin Contributor 并拒绝重名；不保存请求状态。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class BuiltinCommandSource implements CommandDefinitionSource {
    private final List<BuiltinCommandDefinition> definitions;

    public BuiltinCommandSource(List<BuiltinCommandContributor> contributors) {
        TreeMap<String, BuiltinCommandDefinition> unique = new TreeMap<>();
        for (BuiltinCommandContributor contributor : contributors) {
            BuiltinCommandDefinition definition = contributor.definition();
            if (unique.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalStateException("Duplicate builtin command name: " + definition.name());
            }
        }
        definitions = List.copyOf(unique.values());
    }

    @Override
    public CommandKind kind() {
        return CommandKind.BUILTIN;
    }

    @Override
    public List<ResolvedCommandDTO> list(RuntimeSessionDTO session) {
        CommandSessionSnapshotDTO snapshot = CommandSessionSnapshotDTO.from(session);
        return definitions.stream()
                .map(definition -> definition.describe(snapshot))
                .toList();
    }

    @Override
    public List<? extends CommandDefinition> definitions(RuntimeSessionDTO session) {
        return definitions;
    }
}
