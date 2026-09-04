/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.service.command.readonly;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.command.catalog.ResolvedCommandCatalog;
import com.huawei.hicampus.claw.codingagent.runtimeapi.command.type.CommandKind;
import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.HelpCommandResultDTO;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeApiException;
import com.huawei.hicampus.claw.codingagent.runtimeapi.error.RuntimeErrorCode;

import org.springframework.stereotype.Service;

/**
 * 从请求内已有 Catalog 组装 Builtin 帮助，不重新解析任何命令来源。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@Service
public class CommandHelpFormatter {
    public HelpCommandResultDTO query(ResolvedCommandCatalog catalog, String arguments) {
        String name = arguments == null ? "" : arguments.strip();
        if (name.isEmpty()) {
            return new HelpCommandResultDTO(catalog.list().stream()
                    .filter(command -> command.kind() == CommandKind.BUILTIN)
                    .toList());
        }
        if (name.startsWith("/")) {
            throw new RuntimeApiException(RuntimeErrorCode.INVALID_COMMAND_REQUEST);
        }
        return new HelpCommandResultDTO(List.of(catalog.find(name)
                .filter(command -> command.kind() == CommandKind.BUILTIN)
                .orElseThrow(() -> new RuntimeApiException(RuntimeErrorCode.COMMAND_NOT_FOUND))));
    }
}
