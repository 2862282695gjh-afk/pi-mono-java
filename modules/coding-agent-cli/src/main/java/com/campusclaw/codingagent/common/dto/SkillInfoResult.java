/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import java.util.List;

import lombok.Data;

/**
 * Mate 内网网关 {@code GET /mate-service/v1/skill/query/{skillId}} 返回
 * 响应中 {@code result} 字段的 Skill 信息结果。
 *
 * <p>字段对齐 runtime 侧 {@code MateServiceClient.SkillInfo} 的真实契约:
 * {@code bindingTools} 直挂 result(无额外嵌套),元素含 {@code id}。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/22]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class SkillInfoResult {
    private List<SkillBindingTool> bindingTools;
}
