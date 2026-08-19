/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import java.util.List;

import lombok.Data;

/**
 * Mate 内网网关 {@code GET /mate-service/v1/skill/info/query/{skillId}}
 * 返回信封中 {@code result} 字段的 Skill 工具查询结果。
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class QuerySkillToolsResult {
    private List<SkillBindingTool> bindingTools;
}
