/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.runtime;

import java.util.List;

import com.huawei.hicampus.claw.codingagent.runtimeapi.dto.ExecutionTargetDTO;

/**
 * 将已提交的跨实例控制信号交给当前实例的活动执行。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public interface RuntimeLocalControlDispatcher {
    /**
     * 返回当前实例仍持有的固定执行目标，包括正在重试唯一终态提交的目标。
     *
     * @param limit 最大返回数量
     * @return 按稳定顺序排列的活动目标
     */
    List<ExecutionTargetDTO> activeTargets(int limit);

    /**
     * 向完整身份匹配的本机执行发出停止请求。
     *
     * @param target 固定执行目标
     * @return 是否命中并发出了停止请求
     */
    boolean dispatchStop(ExecutionTargetDTO target);

    /**
     * 向完整身份和工具调用均匹配的本机执行交付确认结果。
     *
     * @param confirmingTarget 确认前的固定执行目标
     * @param toolCallId 工具调用标识
     * @return 是否命中并消费了确认结果
     */
    boolean dispatchConfirmation(ExecutionTargetDTO confirmingTarget, String toolCallId);
}
