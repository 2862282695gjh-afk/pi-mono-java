/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * 计划审核等待事务中提交的计划和段终止事件身份。
 *
 * @param planEventId 计划事件标识
 * @param planEventSeq 计划事件序号
 * @param idleEventId 等待段终止事件标识
 * @param idleEventSeq 等待段终止事件序号
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record PlanConfirmingEventsDTO(String planEventId, long planEventSeq, String idleEventId, long idleEventSeq) {}
