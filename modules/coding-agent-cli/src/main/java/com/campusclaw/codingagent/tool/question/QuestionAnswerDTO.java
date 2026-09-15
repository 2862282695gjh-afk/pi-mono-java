/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单题的规范化答案。
 *
 * @param id 问题标识
 * @param selected 按原选项顺序排列的选中标签
 * @param custom 规范化后的可选自由文本
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionAnswerDTO(
        String id, List<String> selected, @JsonInclude(JsonInclude.Include.NON_NULL) String custom) {
    public QuestionAnswerDTO {
        selected = List.copyOf(selected);
    }
}
