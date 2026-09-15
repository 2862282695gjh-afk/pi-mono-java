/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import com.campusclaw.agent.tool.CancellationToken;

/**
 * AskUserQuestion 等待 Runtime 权威答案的窄端口。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/15]
 * @since [br_eCampusCore 26.0.0]
 */
@FunctionalInterface
public interface UserQuestionPort {
    QuestionAnswerResultDTO await(String toolCallId, CancellationToken signal) throws Exception;
}
