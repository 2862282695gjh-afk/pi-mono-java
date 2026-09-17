/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.tool.question;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制临时展示确认适配能力。
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/17]
 * @since [br_eCampusCore 26.0.0]
 */
@ConfigurationProperties(prefix = "campusclaw.showcase")
public class ShowcaseConfirmationProperties {
    private boolean confirmWithUserEnabled;

    public boolean isConfirmWithUserEnabled() {
        return confirmWithUserEnabled;
    }

    public void setConfirmWithUserEnabled(boolean confirmWithUserEnabled) {
        this.confirmWithUserEnabled = confirmWithUserEnabled;
    }
}
