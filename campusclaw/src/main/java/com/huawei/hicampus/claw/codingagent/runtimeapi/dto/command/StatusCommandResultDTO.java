/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command;

/**
 * Status 命令的持久化 Session 状态结果，不包含运行时估算信息。
 *
 * @param state 持久化状态
 * @param modelId 持久化模型标识
 * @param thinking 是否启用 Thinking
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record StatusCommandResultDTO(String state, String modelId, boolean thinking) implements CommandResultDTO {}
