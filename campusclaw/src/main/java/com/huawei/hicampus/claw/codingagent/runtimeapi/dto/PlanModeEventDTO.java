/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 计划批准事务中按根消息构造的模式变更 Entry 与公共事件。
 *
 * @param entry 模式变更 Entry
 * @param event 模式变更公共事件
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record PlanModeEventDTO(RuntimeEntryDTO entry, CommittedEventDTO event) {}
