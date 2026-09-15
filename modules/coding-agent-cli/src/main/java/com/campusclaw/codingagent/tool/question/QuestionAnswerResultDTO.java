/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import java.util.List;

/**
 * 返回给 AskUserQuestion 工具的可信答案集合。
 *
 * @param answers 按问题顺序排列的答案
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionAnswerResultDTO(List<QuestionAnswerDTO> answers) {
    public QuestionAnswerResultDTO {
        answers = List.copyOf(answers);
    }
}
