/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 不可变的问题选项快照。
 *
 * @param label 规范化后的选项标签
 * @param description 规范化后的可选说明
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionOptionDTO(String label, @JsonInclude(JsonInclude.Include.NON_NULL) String description) {}
