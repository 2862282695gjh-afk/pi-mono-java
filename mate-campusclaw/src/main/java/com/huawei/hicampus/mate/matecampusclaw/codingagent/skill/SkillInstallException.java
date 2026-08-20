/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.mate.matecampusclaw.codingagent.skill;

/**
 * Thrown when a skill installation, removal, or linking operation fails.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public class SkillInstallException extends Exception {

    public SkillInstallException(String message) {
        super(message);
    }

    public SkillInstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
