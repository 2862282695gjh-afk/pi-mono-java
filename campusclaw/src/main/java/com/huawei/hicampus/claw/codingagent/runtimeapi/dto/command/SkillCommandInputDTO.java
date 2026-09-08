/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.dto.command;

import java.util.List;

import lombok.Data;

/**
 * 已选择 Skill 类别后的内部执行输入，不携带 HTTP 请求或凭据。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@Data
public class SkillCommandInputDTO {
    private String skillName;

    private String arguments;

    private List<String> fileIds;
}
