/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import lombok.Data;

/**
 * A tool bound to a skill, as declared in the {@code result.bindingTools}
 * array of {@code GET /mate-service/v1/skill/info/query/{skillId}} on the
 * Mate inner gateway. Only {@code id} is consumed for the follow-up
 * QUERYTOOLS call.
 *
 * @version [br_eCampusCore 26.0.0, 2026/08/18]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class SkillBindingTool {

    private String id;

    private String version;

    private String name;

    private String description;

    private String permission;

    private String source;

    private Boolean isConcurrencySafe;
}
