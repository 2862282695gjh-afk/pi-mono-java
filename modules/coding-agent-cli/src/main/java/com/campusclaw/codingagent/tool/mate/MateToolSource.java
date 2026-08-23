/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.tool.mate;

/**
 * 标识 Session 内一个 Mate 工具发现来源，不包含远端资源标识。
 *
 * @param type 来源类型
 * @param name Skill 名称；Agent 来源为空
 * @version [br_eCampusCore 26.0.0, 2026/08/24]
 * @since [br_eCampusCore 26.0.0]
 */
public record MateToolSource(String type, String name) {

    public static MateToolSource agent() {
        return new MateToolSource("agent", null);
    }

    public static MateToolSource skill(String skillName) {
        return new MateToolSource("skill", skillName);
    }
}
