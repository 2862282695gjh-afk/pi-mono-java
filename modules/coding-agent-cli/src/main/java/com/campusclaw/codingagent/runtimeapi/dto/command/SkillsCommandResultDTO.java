/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.dto.command;

import java.util.List;

/**
 * Skills 命令的最小信息结果，不携带绑定标识、版本、路径或正文。
 *
 * @param skills 按名称排序的不可变列表
 * @version [br_eCampusCore 26.0.0, 2026/09/04]
 * @since [br_eCampusCore 26.0.0]
 */
public record SkillsCommandResultDTO(List<SkillDTO> skills) implements CommandResultDTO {
    public SkillsCommandResultDTO {
        skills = List.copyOf(skills);
    }

    /**
     * 单个绑定 Skill 的展示信息。
     *
     * @param name 名称
     * @param description 描述
     */
    public record SkillDTO(String name, String description) {}
}
