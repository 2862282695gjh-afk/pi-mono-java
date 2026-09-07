/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import java.util.List;

import lombok.Getter;

/**
 * 当前 Agent 绑定的 Skill 公开清单，不含绑定快照和内部 DTO。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/07]
 * @since [br_eCampusCore 26.0.0]
 */
@Getter
public final class BoundSkillsResponseVO {
    private final List<SkillResponseVO> skills;

    public BoundSkillsResponseVO(List<SkillResponseVO> skills) {
        this.skills = List.copyOf(skills);
    }

    /**
     * 单个 Skill 的公开名称与说明。
     */
    @Getter
    public static final class SkillResponseVO {
        private final String name;

        private final String description;

        public SkillResponseVO(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }
}
