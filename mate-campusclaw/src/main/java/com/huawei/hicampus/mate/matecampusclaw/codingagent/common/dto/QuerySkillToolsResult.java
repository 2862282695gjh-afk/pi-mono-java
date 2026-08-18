/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.common.dto;

import java.util.List;

import lombok.Data;

/**
 * Skill tool-query result returned in the {@code result} field of
 * {@code GET /mate-service/v1/skill/info/query/{skillId}} on the Mate inner
 * gateway.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class QuerySkillToolsResult {

    private List<SkillBindingTool> bindingTools;
}
