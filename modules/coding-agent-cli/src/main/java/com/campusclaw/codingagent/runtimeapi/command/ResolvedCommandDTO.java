/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.command;

import java.util.List;

/**
 * 单个命令的只读发现结果，供清单与精确查找共享，不包含可变传输对象。
 *
 * @param name 不含前导斜杠的稳定名称
 * @param kind 命令类型
 * @param description 使用说明
 * @param available 无参数时是否可用
 * @param unavailableCode 不可用原因
 * @param input 参数输入描述
 * @param snapshot 发现时的 Skill 版本快照，其他来源为 null
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record ResolvedCommandDTO(
        String name,
        CommandKind kind,
        String description,
        boolean available,
        String unavailableCode,
        InputDTO input,
        SkillCommandSnapshotDTO snapshot) {
    /**
     * 命令参数输入的只读描述。
     *
     * @param mode 小写输入模式
     * @param available 带参数时是否可用
     * @param unavailableCode 不可用原因
     * @param acceptsFiles 是否接受附件
     * @param placeholder 参数提示
     * @param suggestions 不可变建议列表
     */
    public record InputDTO(
            String mode,
            boolean available,
            String unavailableCode,
            boolean acceptsFiles,
            String placeholder,
            List<String> suggestions) {
        public InputDTO {
            suggestions = List.copyOf(suggestions);
        }
    }
}
