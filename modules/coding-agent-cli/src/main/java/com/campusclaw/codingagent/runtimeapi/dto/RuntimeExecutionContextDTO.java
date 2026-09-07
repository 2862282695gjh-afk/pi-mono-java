/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

/**
 * POST Events 已准备但尚未提交给 Agent 的执行上下文。
 *
 * @param holder 活动 Session 引擎句柄
 * @param execution 活动执行状态
 * @param userMessage 交给 Agent 的初始用户消息
 * @param eventStream 本次 POST Events 的请求流
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public record RuntimeExecutionContextDTO(
        RuntimeSessionHolder holder,
        RuntimeActiveExecution execution,
        UserMessage userMessage,
        RuntimeEventStream eventStream) {}
