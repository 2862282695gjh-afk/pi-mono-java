/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.List;
import java.util.Optional;

/**
 * 请求级不可变发现清单；列表与名称查找共享同一次来源解析结果。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public final class ResolvedCommandCatalog {
    private final List<ResolvedCommandDTO> commands;

    ResolvedCommandCatalog(List<ResolvedCommandDTO> commands) {
        this.commands = List.copyOf(commands);
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
