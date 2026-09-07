/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.vo;

import java.util.List;

import lombok.Getter;

/**
 * 当前 Agent 的公开介绍，不包含运行元数据或命令回执。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public final class AgentHelpResponseVO {
    private final String displayName;

    private final List<String> description;

    private final List<String> userCases;

    public AgentHelpResponseVO(String displayName, List<String> description, List<String> userCases) {
        this.displayName = displayName;
        this.description = List.copyOf(description);
        this.userCases = List.copyOf(userCases);
    }
}
