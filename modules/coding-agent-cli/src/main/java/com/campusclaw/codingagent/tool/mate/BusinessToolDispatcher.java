/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

import java.util.Map;
import java.util.Objects;

import com.campusclaw.codingagent.tool.builtin.ToolEntryPoint;
import com.campusclaw.codingagent.tool.question.BusinessConfirmationAdapter;
import com.campusclaw.codingagent.tool.question.UserQuestionPort;
import com.campusclaw.common.constant.ClawConstants;

/**
 * 按固定业务工具名称选择本地处理器，其余调用交给 Mate 远端处理器。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/23]
 * @since [br_eCampusCore 26.0.0]
 */
public final class BusinessToolDispatcher {
    private final Map<String, BusinessToolHandler> handlers;

    private final BusinessToolHandler remote;

    private BusinessToolDispatcher(Map<String, BusinessToolHandler> handlers, BusinessToolHandler remote) {
        this.handlers = Map.copyOf(handlers);
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    static BusinessToolDispatcher assembled(
            ToolEntryPoint entryPoint, UserQuestionPort questions, BusinessToolHandler remote) {
        BusinessToolHandler confirmation = new BusinessConfirmationAdapter(entryPoint, questions);
        return new BusinessToolDispatcher(
                Map.of(ClawConstants.RuntimeApi.BUSINESS_CONFIRMATION_TOOL_NAME, confirmation), remote);
    }

    static BusinessToolDispatcher unbound(BusinessToolHandler remote) {
        return assembled(ToolEntryPoint.RUNTIME, null, remote);
    }

    BusinessToolHandler select(String trustedToolName) {
        return handlers.getOrDefault(trustedToolName, remote);
    }
}
