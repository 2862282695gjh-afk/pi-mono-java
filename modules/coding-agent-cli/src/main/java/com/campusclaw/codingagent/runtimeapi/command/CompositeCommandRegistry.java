/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

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
        List<ResolvedCommandDTO> commands = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CommandDefinitionSource source : sources) {
            for (ResolvedCommandDTO command : source.list(session)) {
                if (!seen.add(command.name())) {
                    throw new IllegalStateException("Duplicate command name: " + command.name());
                }
                commands.add(command);
            }
        }
        commands.sort(Comparator.comparing(ResolvedCommandDTO::name));
        return new ResolvedCommandCatalog(commands);
    }
}
