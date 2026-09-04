/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import com.campusclaw.codingagent.runtimeapi.dto.RuntimeSessionDTO;

/**
 * 请求解析时复制的 Session 观察值，不能替代修改操作的锁内状态复核。
 *
 * @param id Session 标识
 * @param agentId Agent 标识
 * @param state 持久化状态
 * @param modelId 当前模型
 * @param thinking 当前思考开关
 * @param resourceVersion 资源版本
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record CommandSessionSnapshotDTO(
        String id, String agentId, String state, String modelId, boolean thinking, long resourceVersion) {
    public static CommandSessionSnapshotDTO from(RuntimeSessionDTO session) {
        return new CommandSessionSnapshotDTO(
                session.getId(),
                session.getAgentId(),
                session.getState(),
                session.getModelId(),
                session.isThinking(),
                session.getResourceVersion());
    }
}
