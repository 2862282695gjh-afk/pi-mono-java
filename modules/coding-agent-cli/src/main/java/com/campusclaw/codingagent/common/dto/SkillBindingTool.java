/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.common.dto;

import lombok.Data;

/**
 * Mate 内网网关 {@code GET /mate-service/v1/skill/info/query/{skillId}}
 * 返回信封中 {@code result.bindingTools} 数组元素:绑定到 Skill 的工具。
 * 当前仅消费 {@code id}(用于后续 QUERYTOOLS 查询)。
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
