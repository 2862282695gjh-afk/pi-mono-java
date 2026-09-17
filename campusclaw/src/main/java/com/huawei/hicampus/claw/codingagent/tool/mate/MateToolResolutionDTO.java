/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.mate;

/**
 * 单个 Mate 工具调用使用的不可变身份与展示名称快照。
 *
 * @param toolId 内部工具标识
 * @param toolName 模型使用的技术名称
 * @param displayName 可选的用户展示名称
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
public record MateToolResolutionDTO(String toolId, String toolName, String displayName) {}
