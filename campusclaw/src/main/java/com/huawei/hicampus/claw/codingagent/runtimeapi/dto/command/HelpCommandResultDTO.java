/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command;

import java.util.List;

/**
 * Help 命令的内部只读结果，不直接作为 HTTP 响应。
 *
 * @param displayName Agent 显示名，不是 Session 名称
 * @param description 维护者填写的用途介绍
 * @param userCases 维护者填写的适用场景
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record HelpCommandResultDTO(String displayName, List<String> description, List<String> userCases)
        implements CommandResultDTO {
    public HelpCommandResultDTO {
        description = List.copyOf(description);
        userCases = List.copyOf(userCases);
    }
}
