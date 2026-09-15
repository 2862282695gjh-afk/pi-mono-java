/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 行锁内合并得到的 Session 实际配置变化。
 *
 * @param previous 锁内原始 Session 快照
 * @param modelId 合并后的模型
 * @param thinking 合并后的思考开关
 * @param mode 合并后的模式
 * @param modelChanged 模型是否真实变化
 * @param thinkingChanged 思考开关是否真实变化
 * @param thinkingReason 思考变化原因
 * @param modeChanged 模式是否真实变化
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record SessionUpdateChangesDTO(
        RuntimeSessionDTO previous,
        String modelId,
        boolean thinking,
        String mode,
        boolean modelChanged,
        boolean thinkingChanged,
        String thinkingReason,
        boolean modeChanged) {}
