/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.codingagent.runtimeapi.command;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Argument input mode of a slash command descriptor.
 *
 * @version [br_eCampusCore 26.0.0, 2026/09/02]
 * @since [br_eCampusCore 26.0.0]
 */
public enum CommandInputMode {
    NONE("none"),
    OPTIONAL("optional"),
    REQUIRED("required");

    private final String value;

    CommandInputMode(String value) {
        this.value = value;
    }

    /**
     * Returns the lowercase wire value used in descriptors.
     *
     * @return wire value
     */
    @JsonValue
    public String value() {
        return value;
    }
}
