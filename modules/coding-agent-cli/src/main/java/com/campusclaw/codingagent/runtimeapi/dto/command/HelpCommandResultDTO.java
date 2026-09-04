/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto.command;

import java.util.List;

/**
 * Help 命令的内部只读结果，不直接作为 HTTP 响应。
 *
 * @param commands 本次请求内复用的命令描述符
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record HelpCommandResultDTO(List<ResolvedCommandDTO> commands) implements CommandResultDTO {
    public HelpCommandResultDTO {
        commands = List.copyOf(commands);
    }
}
