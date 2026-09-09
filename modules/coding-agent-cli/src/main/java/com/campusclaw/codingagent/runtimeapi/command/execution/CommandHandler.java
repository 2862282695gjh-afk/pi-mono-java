/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command.execution;

import java.util.concurrent.CompletionStage;

import com.campusclaw.codingagent.runtimeapi.dto.command.CommandResultDTO;

/**
 * 以请求上下文和已归一化参数执行命令，返回内部结果的完成句柄。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface CommandHandler {
    CompletionStage<? extends CommandResultDTO> execute(CommandExecutionContext context, String arguments);
}
