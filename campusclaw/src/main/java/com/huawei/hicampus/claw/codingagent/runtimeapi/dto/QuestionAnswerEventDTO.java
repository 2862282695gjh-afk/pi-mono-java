/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto;

/**
 * 行锁内生成并提交的用户问题答案回执。
 *
 * @param entry 回执 Entry
 * @param event 完整公共事件
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionAnswerEventDTO(RuntimeEntryDTO entry, CommittedEventDTO event) {}
