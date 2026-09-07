/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

import com.campusclaw.ai.types.UserMessage;
import com.campusclaw.codingagent.runtimeapi.event.RuntimeEventStream;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeActiveExecution;
import com.campusclaw.codingagent.runtimeapi.runtime.RuntimeSessionHolder;

/**
 * 普通消息或 Skill 已准备但尚未提交给 Agent 的执行上下文。
 *
 * @param holder 活动 Session 引擎句柄
 * @param execution 活动执行状态
 * @param userMessage 交给 Agent 的初始用户消息
 * @param message 同次准备的原始消息文本，用于权威 Entry 保存
 * @param eventStream 本次普通消息执行的请求流
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
public record RuntimeExecutionContextDTO(
        RuntimeSessionHolder holder,
        RuntimeActiveExecution execution,
        UserMessage userMessage,
        String message,
        RuntimeEventStream eventStream) {}
