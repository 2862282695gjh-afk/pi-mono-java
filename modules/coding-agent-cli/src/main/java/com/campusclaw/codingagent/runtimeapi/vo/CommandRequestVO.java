/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.codingagent.runtimeapi.vo;

import com.campusclaw.codingagent.runtimeapi.web.json.CommandRequestDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 共享命令请求的联合边界，以名称命名空间区分 Builtin 和 Skill 的字段约束。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/08]
 * @since [br_eCampusCore 26.0.0]
 */
@JsonDeserialize(using = CommandRequestDeserializer.class)
public sealed interface CommandRequestVO permits BuiltinCommandRequestVO, SkillCommandRequestVO {
    String getName();

    String getArguments();
}
