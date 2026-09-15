/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * 原子提交的问题和 confirming 完整事件身份。
 *
 * @param questionEventId 问题事件标识
 * @param questionEventSeq 问题事件序号
 * @param idleEventId confirming 事件标识
 * @param idleEventSeq confirming 事件序号
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionConfirmingEventsDTO(
        String questionEventId, long questionEventSeq, String idleEventId, long idleEventSeq) {}
