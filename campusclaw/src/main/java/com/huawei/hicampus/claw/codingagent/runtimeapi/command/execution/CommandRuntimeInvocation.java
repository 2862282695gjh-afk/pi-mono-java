/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command.execution;

import java.util.Locale;
import java.util.concurrent.CompletionStage;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command.CommandResultDTO;

/**
 * 一次请求的 Runtime 调用能力，不向命令上下文暴露凭据或活动执行对象。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface CommandRuntimeInvocation {
    CompletionStage<? extends CommandResultDTO> invoke(String sessionId, Locale locale);
}
