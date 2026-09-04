/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto.command;

import java.util.List;

import com.campusclaw.codingagent.runtimeapi.command.type.CommandInputMode;

/**
 * Builtin 的固定元数据，不含请求状态和运行依赖。
 *
 * @param name 命令名称
 * @param description 描述
 * @param inputMode 参数输入模式
 * @param placeholder 参数提示
 * @param suggestions 参数建议
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record BuiltinCommandMetadataDTO(
        String name, String description, CommandInputMode inputMode, String placeholder, List<String> suggestions) {
    public BuiltinCommandMetadataDTO {
        suggestions = List.copyOf(suggestions);
    }
}
