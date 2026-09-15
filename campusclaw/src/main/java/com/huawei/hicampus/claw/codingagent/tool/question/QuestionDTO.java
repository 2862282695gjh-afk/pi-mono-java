/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.question;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 不可变的单题快照。
 *
 * @param id 模型声明且保持原样的问题标识
 * @param question 规范化后的问题正文
 * @param header 规范化后的可选标题
 * @param options 保持模型声明顺序的选项
 * @param multiSelect 是否允许选择多个选项
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionDTO(
        String id,
        String question,
        @JsonInclude(JsonInclude.Include.NON_NULL) String header,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<QuestionOptionDTO> options,
        boolean multiSelect) {
    public QuestionDTO {
        options = List.copyOf(options);
    }
}
