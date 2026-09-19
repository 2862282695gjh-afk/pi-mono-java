/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto;

/**
 * 单次 Assistant 响应中计划标记解析后的不可变文本快照。
 *
 * @param messageContent 标记块之前的普通正文
 * @param planContent 去除标记行后的计划正文
 * @param valid 是否存在唯一且完整合法的计划块
 * @version [br_eCampusCore 26.0.0, 2026/09/19]
 * @since [br_eCampusCore 26.0.0]
 */
public record PlanTextSnapshotDTO(String messageContent, String planContent, boolean valid) {}
