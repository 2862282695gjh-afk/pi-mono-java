/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.skill;

import java.nio.file.Path;

/**
 * 从 SKILL.md 加载的自包含能力包，用于提供专用工作流和指令。
 *
 * @param name Skill 标识，由小写字母、数字和连字符组成
 * @param description Skill 描述
 * @param filePath SKILL.md 路径
 * @param baseDir SKILL.md 所在目录
 * @param source Skill 来源
 * @param disableModelInvocation 是否禁止模型调用
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record Skill(
        String name, String description, Path filePath, Path baseDir, String source, boolean disableModelInvocation) {}
