/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.question;

import java.util.List;

/**
 * 单次 AskUserQuestion 调用的不可变问题快照。
 *
 * @param questions 保持调用顺序的问题
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
public record QuestionRequestSnapshotDTO(List<QuestionDTO> questions) {
    public QuestionRequestSnapshotDTO {
        questions = List.copyOf(questions);
    }
}
