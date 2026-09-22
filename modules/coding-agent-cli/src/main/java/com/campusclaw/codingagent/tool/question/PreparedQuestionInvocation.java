/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.question;

import com.campusclaw.agent.tool.PreparedToolInvocation;

/**
 * 向 Host 暴露不可变问题快照的已准备工具调用。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/23]
 * @since [br_eCampusCore 26.0.0]
 */
public interface PreparedQuestionInvocation extends PreparedToolInvocation {

    QuestionRequestSnapshotDTO questionRequest();
}
