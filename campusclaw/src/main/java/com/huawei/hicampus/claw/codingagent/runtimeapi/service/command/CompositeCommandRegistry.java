/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import com.huawei.hicampus.claw.codingagent.runtime.PreparedAgentRuntime;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.definition.CommandDefinition;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.source.CommandDefinitionSource;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandKind;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.RuntimeSessionDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandSessionSnapshotDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.ResolvedCommandDTO;

import org.springframework.stereotype.Service;

/**
 * 聚合发现来源，每次请求只解析一次不可变清单，供列表与名称查找共享。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CompositeCommandRegistry {
    private final List<CommandDefinitionSource> sources;

    public CompositeCommandRegistry(List<CommandDefinitionSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * 解析当前请求可见的命令清单。
     *
     * @param session 已经完成访问检查的 Session
     * @return 经过查重并按名称排序的只读清单
     * @throws IllegalStateException 不同来源贡献相同命令名时抛出
     */
    public ResolvedCommandCatalog resolve(RuntimeSessionDTO session) {
        return resolve(session, EnumSet.allOf(CommandKind.class));
    }

    public ResolvedCommandCatalog resolve(RuntimeSessionDTO session, CommandKind kind) {
        return resolve(session, Set.of(kind));
    }

    /**
     * 以应用层捕获的完整快照解析全部来源，不重新读取 Agent。
     *
     * @param session 本次 Session 观察值
     * @param prepared 已经 Manager 完整性与目录身份校验的快照
     * @return 共享同源快照与 Definition 身份的完整清单
     * @throws IllegalArgumentException 快照不完整或与 Session Agent 身份不一致
     * @throws IllegalStateException 来源类型不一致或命令重名
     */
    public ResolvedCommandCatalog resolveComplete(RuntimeSessionDTO session, PreparedAgentRuntime prepared) {
        Objects.requireNonNull(prepared);
        if (prepared.metadata() == null
                || !Objects.equals(session.getAgentId(), prepared.agentId())
                || !Objects.equals(session.getAgentId(), prepared.metadata().id())) {
            throw new IllegalArgumentException("Complete snapshot does not match Session Agent");
        }
        return resolve(session, EnumSet.allOf(CommandKind.class), source -> source.definitions(session, prepared));
    }

    private ResolvedCommandCatalog resolve(RuntimeSessionDTO session, Set<CommandKind> kinds) {
        return resolve(session, kinds, source -> source.definitions(session));
    }

    private ResolvedCommandCatalog resolve(
            RuntimeSessionDTO session,
            Set<CommandKind> kinds,
            Function<CommandDefinitionSource, List<? extends CommandDefinition>> definitionsForSource) {
        CommandSessionSnapshotDTO snapshot = CommandSessionSnapshotDTO.from(session);
        TreeMap<String, ResolvedCommandDTO> commands = new TreeMap<>();
        TreeMap<String, CommandDefinition> definitions = new TreeMap<>();
        for (CommandDefinitionSource source : sources) {
            CommandKind sourceKind = Objects.requireNonNull(source.kind());
            if (!kinds.contains(sourceKind)) {
                continue;
            }
            for (CommandDefinition definition : definitionsForSource.apply(source)) {
                ResolvedCommandDTO command = definition.describe(snapshot);
                if (command.kind() != sourceKind) {
                    throw new IllegalStateException("Command kind differs from its source: " + command.name());
                }
                if (commands.putIfAbsent(command.name(), command) != null) {
                    throw new IllegalStateException("Duplicate command name: " + command.name());
                }
                definitions.put(command.name(), definition);
            }
        }
        return new ResolvedCommandCatalog(snapshot, List.copyOf(commands.values()), definitions);
    }
}
