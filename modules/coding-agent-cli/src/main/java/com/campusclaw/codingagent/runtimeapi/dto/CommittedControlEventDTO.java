/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * 已提交控制事件的内部身份。
 *
 * @param eventId 公共事件标识
 * @param eventSeq Session 内部提交顺序号
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
public record CommittedControlEventDTO(String eventId, long eventSeq) {}
